package com.ecommerce.order.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private const val CHANGED_PRICE = 15_900L
private const val UNPROCESSABLE = 422
private const val DECLINED = "payment-declined"
private val UNPROCESSABLE_STATUS: HttpStatus = HttpStatus.valueOf(UNPROCESSABLE)
private val QUIET: Duration = Duration.ofSeconds(2)

/** Checkout outcomes, idempotency and the race for the last unit (T063) against the real database and broker. */
@Suppress("TooManyFunctions") // one test per checkout outcome of order.yaml
class CheckoutConcurrencyIT : OrderIntegrationTest() {
    @Test
    fun `an approved checkout answers 201, commits the stock, clears the cart and publishes its events`() {
        val shopper = stubs.checkoutOf(Shopper())

        val result =
            placeOrder(shopper)
                .expectStatus()
                .isCreated
                .expectHeader()
                .exists(HttpHeaders.LOCATION)
                .expectBody(MEMBERS)
                .returnResult()
        val order = checkNotNull(result.responseBody)
        val orderId = order["id"].toString()

        order["orderStatus"] shouldBe "placed"
        order["paymentStatus"] shouldBe "approved"
        order["orderNumber"].toString() shouldStartWith "ORD-"
        result.responseHeaders.location.toString() shouldBe "$ORDERS/$orderId"
        recorded.awaitType(EventType.OrderPlaced) { it.aggregateId.toString() == orderId }
        val paid = recorded.awaitType(EventType.OrderPaid) { it.aggregateId.toString() == orderId }
        paid.payload["paymentStatus"].asString() shouldBe "approved"
        paid.payload["recipient"]["email"].asString() shouldBe "ada@example.test"
        stubs.reservationCalls(shopper, "commit") shouldBe 1
        stubs.clearCalls(shopper) shouldBe 1
        column(
            "SELECT response_status FROM idempotency_records WHERE order_id = :id",
            "id" to UUID.fromString(orderId),
        ) shouldBe
            listOf(HttpStatus.CREATED.value())
    }

    @Test
    fun `a declined payment answers 422 payment-declined, releases the stock and keeps the cart`() {
        val shopper = stubs.checkoutOf(Shopper())

        val problem = placeOrder(shopper, DECLINED_TOKEN).expectOrderProblem(UNPROCESSABLE_STATUS, DECLINED)

        problem["type"] shouldBe "https://ecommerce.example/problems/payment-declined"
        problem["declineReason"] shouldBe "card_rejected"
        val orderId = problem["orderId"].toString()
        val order = body(getOrder(orderId, shopper.accountId).expectStatus().isOk)
        order["orderStatus"] shouldBe "cancelled"
        order["paymentStatus"] shouldBe "failed"
        order["cancellationReason"] shouldBe "PAYMENT_FAILED"
        recorded.awaitType(EventType.OrderPaymentFailed) { it.aggregateId.toString() == orderId }
        stubs.reservationCalls(shopper, "release") shouldBe 1
        stubs.clearCalls(shopper) shouldBe 0
        column(
            "SELECT response_status FROM idempotency_records WHERE order_id = :id",
            "id" to UUID.fromString(orderId),
        ) shouldBe
            listOf(UNPROCESSABLE)
    }

    @Test
    fun `an unreachable provider answers 202 with Retry-After and the payment pending`() {
        val shopper = stubs.checkoutOf(Shopper())

        val order =
            body(
                placeOrder(shopper, UNREACHABLE_TOKEN)
                    .expectStatus()
                    .isAccepted
                    .expectHeader()
                    .exists(HttpHeaders.RETRY_AFTER),
            )

        order["paymentStatus"] shouldBe "pending"
        val orderId = order["id"].toString()
        recorded.awaitType(EventType.OrderPlaced) { it.aggregateId.toString() == orderId }
        stubs.reservationCalls(shopper, "commit") shouldBe 0
        stubs.reservationCalls(shopper, "release") shouldBe 0
        column(
            "SELECT response_status FROM idempotency_records WHERE order_id = :id",
            "id" to UUID.fromString(orderId),
        ) shouldBe
            listOf(HttpStatus.ACCEPTED.value())
    }

    @Test
    fun `a stock shortage answers 409 insufficient-stock and stores nothing`() {
        val shopper = stubs.checkoutOf(Shopper(quantity = 2))
        stubs.refuseReservation(shopper)

        val problem = placeOrder(shopper).expectProblem(ProblemType.INSUFFICIENT_STOCK)

        problem["unavailableLines"] shouldBe
            listOf(
                mapOf(
                    "productId" to shopper.productId.toString(),
                    "name" to "Espresso Machine",
                    "requestedQuantity" to 2,
                    "availableQuantity" to 0,
                ),
            )
        column("SELECT count(*) FROM idempotency_records WHERE account_id = :a", "a" to shopper.accountId) shouldBe
            listOf(0L)
        column("SELECT count(*) FROM orders WHERE account_id = :a", "a" to shopper.accountId) shouldBe listOf(0L)
    }

    @Test
    fun `a stale cart revision answers 409 price-changed with the changed lines and the current revision`() {
        val shopper = stubs.checkoutOf(Shopper(priceMinor = CHANGED_PRICE), priceAtAdd = Shopper().priceMinor)

        val problem = placeOrder(shopper, revision = "rev-seen-before").expectProblem(ProblemType.PRICE_CHANGED)

        problem["currentCartRevision"] shouldBe shopper.revision
        val line = (problem["changedLines"] as List<*>).single() as Map<*, *>
        line["productId"] shouldBe shopper.productId.toString()
        (line["oldPrice"] as Map<*, *>)["amountMinor"] shouldBe Shopper().priceMinor.toInt()
        (line["newPrice"] as Map<*, *>)["amountMinor"] shouldBe CHANGED_PRICE.toInt()
        stubs.reservationCalls(shopper, "commit") shouldBe 0
        column("SELECT count(*) FROM idempotency_records WHERE account_id = :a", "a" to shopper.accountId) shouldBe
            listOf(0L)
    }

    @Test
    fun `the same key and body replays the answer, a different body with the same key is 422`() {
        val shopper = stubs.checkoutOf(Shopper())
        val key = UUID.randomUUID()

        val first = body(placeOrder(shopper, key = key).expectStatus().isCreated)
        val second =
            placeOrder(shopper, key = key)
                .expectStatus()
                .isCreated
                .expectHeader()
                .valueEquals(HttpHeaders.LOCATION, "$ORDERS/${first["id"]}")
                .expectBody(MEMBERS)
                .returnResult()
                .responseBody

        second shouldBe first
        stubs.chargeCalls(shopper) shouldBe 1
        val reused =
            placeOrder(
                shopper,
                DECLINED_TOKEN,
                key,
            ).expectOrderProblem(UNPROCESSABLE_STATUS, "idempotency-key-reuse")
        reused["type"] shouldBe "https://ecommerce.example/problems/idempotency-key-reuse"
        reused["idempotencyConflict"] shouldBe true
        column("SELECT count(*) FROM orders WHERE account_id = :a", "a" to shopper.accountId) shouldBe listOf(1L)
    }

    @Test
    fun `a replayed decline answers the same 422`() {
        val shopper = stubs.checkoutOf(Shopper())
        val key = UUID.randomUUID()
        val first = placeOrder(shopper, DECLINED_TOKEN, key).expectOrderProblem(UNPROCESSABLE_STATUS, DECLINED)
        val second = placeOrder(shopper, DECLINED_TOKEN, key).expectOrderProblem(UNPROCESSABLE_STATUS, DECLINED)
        second["orderId"] shouldBe first["orderId"]
        second["declineReason"] shouldBe "card_rejected"
        stubs.chargeCalls(shopper) shouldBe 1
    }

    @Test
    fun `concurrent duplicates with the same key create one order`() {
        val shopper = stubs.checkoutOf(Shopper())
        val key = UUID.randomUUID()

        val statuses = race(2) { placeOrder(shopper, key = key).returnResult(String::class.java).status.value() }

        statuses shouldBe listOf(HttpStatus.CREATED.value(), HttpStatus.CREATED.value())
        column("SELECT count(*) FROM orders WHERE account_id = :a", "a" to shopper.accountId) shouldBe listOf(1L)
        stubs.chargeCalls(shopper) shouldBe 1
    }

    @Test
    fun `two shoppers racing for the last unit get exactly one order`() {
        val productId = UUID.randomUUID()
        val shoppers = listOf(Shopper(productId = productId), Shopper(productId = productId))
        shoppers.forEach { stubs.checkoutOf(it) }
        stubs.lastUnit(productId, shoppers)

        val statuses = race(2) { index -> placeOrder(shoppers[index]).returnResult(String::class.java).status.value() }

        statuses shouldContainExactlyInAnyOrder listOf(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value())
        val placed =
            shoppers.flatMap { column("SELECT id FROM orders WHERE account_id = :a", "a" to it.accountId) }
        placed shouldHaveSize 1
        recorded.awaitType(EventType.OrderPaid) { it.aggregateId == placed.single() }.shouldNotBeNull()
        recorded.expectNone(QUIET) { record ->
            record.headers["type"] == EventType.OrderPlaced.name &&
                record.key != placed.single().toString() &&
                shoppers.any { record.value.contains(it.accountId.toString()) }
        }
    }

    @Test
    fun `checkout requires a shopper token and an Idempotency-Key`() {
        val shopper = stubs.checkoutOf(Shopper())
        client
            .post()
            .uri(ORDERS)
            .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
            .bodyValue(mapOf("addressId" to shopper.addressId.toString()))
            .exchange()
            .expectProblem(ProblemType.VALIDATION, HttpStatus.BAD_REQUEST.value())
        client
            .post()
            .uri(ORDERS)
            .header(AUTHORIZATION, bearer(shopper.accountId, OPERATOR))
            .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
            .bodyValue(mapOf("addressId" to shopper.addressId.toString()))
            .exchange()
            .expectProblem(ProblemType.FORBIDDEN)
        client
            .post()
            .uri(ORDERS)
            .bodyValue(mapOf("addressId" to shopper.addressId.toString()))
            .exchange()
            .expectProblem(ProblemType.UNAUTHORIZED)
    }

    /** Runs [count] calls at the same time and returns their results in call order. */
    private fun <T> race(
        count: Int,
        call: (Int) -> T,
    ): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)
        return try {
            val futures =
                (0 until count).map { index ->
                    executor.submit(
                        Callable {
                            start.await()
                            call(index)
                        },
                    )
                }
            start.countDown()
            futures.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }
}

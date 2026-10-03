package com.ecommerce.order.infrastructure

import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val WAIT: Duration = Duration.ofSeconds(30)
private const val UNPROCESSABLE = 422
private const val ABANDONED_SECONDS = 180L
private const val CHECKOUT_TIMEOUT_SECONDS = 30L

/**
 * The checkout claim against the real database (T141, T156): a failing dependency frees the key, an abandoned claim
 * that already created an order is resumed instead of placing a second one, a cancellation that wins the race against
 * the charge answers 409 `order-cancelled`, and expired records are purged (T139).
 */
class CheckoutClaimIT : OrderIntegrationTest() {
    private fun records(shopper: Shopper): List<Any?> =
        column("SELECT order_id FROM idempotency_records WHERE account_id = :a", "a" to shopper.accountId)

    private fun orderCount(shopper: Shopper): List<Any?> =
        column("SELECT count(*) FROM orders WHERE account_id = :a", "a" to shopper.accountId)

    @Test
    fun `a dependency answering 503 frees the key, so the same key checks out once it recovers`() {
        val shopper = stubs.checkoutOf(Shopper())
        stubs.identityUnavailable(shopper)
        val key = UUID.randomUUID()

        placeOrder(shopper, key = key).expectProblem(ProblemType.UNAVAILABLE)

        records(shopper) shouldBe emptyList()
        stubs.reservationCalls(shopper, "commit") shouldBe 0
        stubs.identity(shopper)
        placeOrder(shopper, key = key).expectStatus().isCreated
        orderCount(shopper) shouldBe listOf(1L)
    }

    @Test
    fun `an abandoned claim that created an order is resumed by the same request, refused to another one`() {
        val shopper = stubs.checkoutOf(Shopper())
        val key = UUID.randomUUID()
        val orderId = body(placeOrder(shopper, UNREACHABLE_TOKEN, key).expectStatus().isAccepted)["id"].toString()
        // What a request that crashed after storing its order leaves behind, two minutes and more ago.
        column(
            "UPDATE idempotency_records SET response_status = NULL, response_body = NULL, created_at = :at " +
                "WHERE account_id = :a RETURNING order_id",
            "at" to Instant.now().minusSeconds(ABANDONED_SECONDS),
            "a" to shopper.accountId,
        ) shouldBe listOf(UUID.fromString(orderId))

        placeOrder(shopper, DECLINED_TOKEN, key).expectOrderProblem(
            HttpStatus.valueOf(UNPROCESSABLE),
            "idempotency-key-reuse",
        )
        val resumed = body(placeOrder(shopper, UNREACHABLE_TOKEN, key).expectStatus().isAccepted)

        resumed["id"] shouldBe orderId
        orderCount(shopper) shouldBe listOf(1L)
        stubs.chargeCalls(shopper) shouldBe 1
        column(
            "SELECT response_status FROM idempotency_records WHERE account_id = :a",
            "a" to shopper.accountId,
        ) shouldBe listOf(HttpStatus.ACCEPTED.value())
    }

    @Test
    fun `a cancellation that wins the race against the charge answers 409 order-cancelled, also on replay`() {
        val shopper = stubs.checkoutOf(Shopper())
        val key = UUID.randomUUID()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val checkout = executor.submit<Map<String, Any?>> { cancelled(placeOrder(shopper, SLOW_TOKEN, key)) }
            await().atMost(WAIT).until { orderCount(shopper) == listOf(1L) }
            val orderId = column("SELECT id FROM orders WHERE account_id = :a", "a" to shopper.accountId).single()

            client
                .post()
                .uri("$ORDERS/$orderId/cancellation")
                .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
                .exchange()
                .expectStatus()
                .isOk

            val problem = checkout.get(CHECKOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            problem["orderId"] shouldBe orderId.toString()
            problem["cancellationReason"] shouldBe "SHOPPER_REQUEST"
            problem["declineReason"] shouldBe null
            cancelled(placeOrder(shopper, SLOW_TOKEN, key))["orderId"] shouldBe orderId.toString()
            val order = body(getOrder(orderId.toString(), shopper.accountId))
            order["orderStatus"] shouldBe "cancelled"
            order["paymentStatus"] shouldBe "failed"
            stubs.reservationCalls(shopper, "commit") shouldBe 0
            stubs.clearCalls(shopper) shouldBe 0
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the purge job deletes idempotency records whose 24 hours ended and keeps live ones`() {
        val expired = insertRecord(Instant.now().minus(IdempotencyRecord.RETENTION).minus(Duration.ofHours(1)))
        val live = insertRecord(Instant.now().minus(IdempotencyRecord.RETENTION).plus(Duration.ofHours(1)))

        await().atMost(WAIT).until { recordsOf(expired) == listOf(0L) }

        recordsOf(live) shouldBe listOf(1L)
    }

    private fun cancelled(spec: WebTestClient.ResponseSpec): Map<String, Any?> =
        spec.expectOrderProblem(HttpStatus.CONFLICT, "order-cancelled")

    private fun insertRecord(createdAt: Instant): UUID {
        val accountId = UUID.randomUUID()
        column(
            "INSERT INTO idempotency_records (account_id, idempotency_key, request_hash, created_at, expires_at) " +
                "VALUES (:a, :k, 'hash', :created, :expires) RETURNING account_id",
            "a" to accountId,
            "k" to UUID.randomUUID(),
            "created" to createdAt,
            "expires" to createdAt.plus(IdempotencyRecord.RETENTION),
        )
        return accountId
    }

    private fun recordsOf(accountId: UUID): List<Any?> =
        column("SELECT count(*) FROM idempotency_records WHERE account_id = :a", "a" to accountId)
}

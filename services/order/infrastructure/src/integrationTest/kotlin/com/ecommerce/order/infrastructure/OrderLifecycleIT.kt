package com.ecommerce.order.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private val WAIT: Duration = Duration.ofSeconds(30)
private val QUIET: Duration = Duration.ofSeconds(2)
private const val SEND_TIMEOUT_SECONDS = 10L
private const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"

/** History, cancellation, operator transitions, the expiry job and the event consumers (US5, T081..T083). */
@Suppress("TooManyFunctions") // one test per lifecycle rule of order.yaml and events.yaml
class OrderLifecycleIT : OrderIntegrationTest() {
    private val operatorId: UUID = UUID.randomUUID()

    @Test
    fun `the history lists the shopper's own orders newest first, other shoppers get 404`() {
        val shopper = stubs.checkoutOf(Shopper())
        val first = paidOrder(shopper).second
        val second = paidOrder(shopper).second
        val (stranger, foreign) = paidOrder()

        val page =
            body(
                client
                    .get()
                    .uri("$ORDERS?page=0&size=10")
                    .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
                    .exchange()
                    .expectStatus()
                    .isOk,
            )

        (page["items"] as List<*>).map { (it as Map<*, *>)["id"] } shouldContainExactly listOf(second, first)
        page["totalItems"] shouldBe 2
        getOrder(foreign, shopper.accountId).expectProblem(ProblemType.NOT_FOUND)
        getOrder(foreign, stranger.accountId).expectStatus().isOk
        getOrder(foreign, operatorId, OPERATOR).expectStatus().isOk
        client
            .get()
            .uri("$ORDERS?size=101")
            .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
            .exchange()
            .expectProblem(ProblemType.VALIDATION, HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `an operator moves a paid order to preparing and shipped, each change is recorded and published`() {
        val (shopper, orderId) = paidOrder()

        transition(orderId, "preparing").expectStatus().isOk
        val shipped = body(transition(orderId, "shipped").expectStatus().isOk)

        shipped["orderStatus"] shouldBe "shipped"
        val change = (shipped["statusHistory"] as List<*>).last() as Map<*, *>
        change["kind"] shouldBe "order"
        change["status"] shouldBe "shipped"
        change["by"] shouldBe operatorId.toString()
        recorded.awaitType(EventType.OrderPreparing) { it.aggregateId.toString() == orderId }
        val event = recorded.awaitType(EventType.OrderShipped) { it.aggregateId.toString() == orderId }
        event.payload["changedBy"].asString() shouldBe operatorId.toString()
        event.payload["paymentStatus"].asString() shouldBe "approved"
        cancel(orderId, shopper).expectOrderProblem(HttpStatus.CONFLICT, "order-not-cancellable")
        transition(orderId, "placed").expectOrderProblem(HttpStatus.CONFLICT, "invalid-transition")
        body(getOrder(orderId, shopper.accountId))["orderStatus"] shouldBe "shipped"
    }

    @Test
    fun `preparing is refused while the payment is pending, and shoppers cannot transition`() {
        val shopper = stubs.checkoutOf(Shopper())
        val orderId = body(placeOrder(shopper, UNREACHABLE_TOKEN).expectStatus().isAccepted)["id"].toString()

        transition(orderId, "preparing").expectOrderProblem(HttpStatus.CONFLICT, "invalid-transition")
        client
            .post()
            .uri("$ORDERS/$orderId/status")
            .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
            .bodyValue(mapOf("orderStatus" to "cancelled"))
            .exchange()
            .expectProblem(ProblemType.FORBIDDEN)
        body(getOrder(orderId, shopper.accountId))["paymentStatus"] shouldBe "pending"
    }

    @Test
    fun `a shopper cancels a paid order OrderCancelled requires a refund and the committed stock is not released`() {
        val (shopper, orderId) = paidOrder()

        val cancelled = body(cancel(orderId, shopper).expectStatus().isOk)

        cancelled["orderStatus"] shouldBe "cancelled"
        cancelled["cancellationReason"] shouldBe "SHOPPER_REQUEST"
        cancelled["paymentStatus"] shouldBe "approved"
        val event = recorded.awaitType(EventType.OrderCancelled) { it.aggregateId.toString() == orderId }
        event.payload["refundRequired"].asBoolean() shouldBe true
        event.payload["reason"].asString() shouldBe "SHOPPER_REQUEST"
        stubs.reservationCalls(shopper, "release") shouldBe 0
    }

    @Test
    fun `cancelling an unpaid order voids the payment and releases the stock`() {
        val shopper = stubs.checkoutOf(Shopper())
        val orderId = body(placeOrder(shopper, UNREACHABLE_TOKEN).expectStatus().isAccepted)["id"].toString()

        body(cancel(orderId, shopper).expectStatus().isOk)["paymentStatus"] shouldBe "failed"

        stubs.reservationCalls(shopper, "release") shouldBe 1
        recorded
            .awaitType(EventType.OrderCancelled) { it.aggregateId.toString() == orderId }
            .payload["refundRequired"]
            .asBoolean() shouldBe false
    }

    @Test
    fun `the expiry job cancels a payment pending for 30 minutes with PAYMENT_EXPIRED`() {
        val shopper = stubs.checkoutOf(Shopper())
        val orderId = body(placeOrder(shopper, UNREACHABLE_TOKEN).expectStatus().isAccepted)["id"].toString()
        column(
            "UPDATE orders SET payment_expires_at = :past WHERE id = :id RETURNING id",
            "past" to Instant.now().minusSeconds(1),
            "id" to UUID.fromString(orderId),
        )

        val event = recorded.awaitType(EventType.OrderCancelled) { it.aggregateId.toString() == orderId }

        event.payload["reason"].asString() shouldBe "PAYMENT_EXPIRED"
        event.payload["paymentStatus"].asString() shouldBe "failed"
        body(getOrder(orderId, shopper.accountId))["cancellationReason"] shouldBe "PAYMENT_EXPIRED"
        await().atMost(WAIT).until { stubs.reservationCalls(shopper, "release") == 1 }
    }

    @Test
    fun `a PaymentApproved event for a pending order approves it once, even when delivered twice`() {
        val shopper = stubs.checkoutOf(Shopper())
        val orderId = body(placeOrder(shopper, UNREACHABLE_TOKEN).expectStatus().isAccepted)["id"].toString()
        val paymentId = UUID.randomUUID()
        val approved = paymentEvent(EventType.PaymentApproved, paymentId, orderId)

        send(approved)
        send(approved)

        val paid = recorded.awaitType(EventType.OrderPaid) { it.aggregateId.toString() == orderId }
        paid.correlationId shouldBe CORRELATION_ID
        paid.payload["paymentId"].asString() shouldBe paymentId.toString()
        await().during(QUIET).atMost(WAIT).until {
            recorded.ofType(EventType.OrderPaid).count { it.aggregateId.toString() == orderId } == 1
        }
        await().atMost(WAIT).until { body(getOrder(orderId, shopper.accountId))["paymentStatus"] == "approved" }
    }

    @Test
    fun `a PaymentDeclined event cancels a pending order with PAYMENT_FAILED`() {
        val shopper = stubs.checkoutOf(Shopper())
        val orderId = body(placeOrder(shopper, UNREACHABLE_TOKEN).expectStatus().isAccepted)["id"].toString()

        send(paymentEvent(EventType.PaymentDeclined, UUID.randomUUID(), orderId, "insufficient_funds"))

        val failed = recorded.awaitType(EventType.OrderPaymentFailed) { it.aggregateId.toString() == orderId }
        failed.payload["reasonCategory"].asString() shouldBe "insufficient_funds"
        body(getOrder(orderId, shopper.accountId))["cancellationReason"] shouldBe "PAYMENT_FAILED"
    }

    @Test
    fun `RefundRecorded is recorded once and AccountDeleted anonymises the account's orders`() {
        val (shopper, orderId) = paidOrder()
        body(cancel(orderId, shopper).expectStatus().isOk)
        val refund =
            envelope(
                EventType.RefundRecorded,
                UUID.randomUUID(),
                mapOf(
                    "refundId" to UUID.randomUUID().toString(),
                    "orderId" to orderId,
                    "paymentId" to UUID.randomUUID().toString(),
                ),
            )
        send(refund)
        send(refund)
        val deleted =
            envelope(
                EventType.AccountDeleted,
                shopper.accountId,
                mapOf(
                    "accountId" to shopper.accountId.toString(),
                    "pseudonym" to "anon-4f9c2d71",
                    "deletedAt" to "2026-10-02T12:00:00Z",
                ),
            )
        send(deleted)

        await().atMost(WAIT).until {
            column("SELECT account_pseudonym FROM orders WHERE id = :id", "id" to UUID.fromString(orderId)) ==
                listOf("anon-4f9c2d71")
        }
        await().atMost(WAIT).until {
            column("SELECT refund_id FROM orders WHERE id = :id", "id" to UUID.fromString(orderId)).single() != null
        }
        column("SELECT recipient_name FROM orders WHERE id = :id", "id" to UUID.fromString(orderId)) shouldBe
            listOf("[redacted]")
        column("SELECT recipient_email FROM orders WHERE id = :id", "id" to UUID.fromString(orderId)) shouldBe
            listOf("anon-4f9c2d71@anonymised.invalid")
        column("SELECT count(*) FROM processed_event WHERE event_id = :id", "id" to refund.eventId) shouldBe listOf(1L)
    }

    private fun transition(
        orderId: String,
        status: String,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("$ORDERS/$orderId/status")
            .header(AUTHORIZATION, bearer(operatorId, OPERATOR))
            .bodyValue(mapOf("orderStatus" to status))
            .exchange()

    private fun cancel(
        orderId: String,
        shopper: Shopper,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("$ORDERS/$orderId/cancellation")
            .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
            .exchange()

    private fun paymentEvent(
        type: EventType,
        paymentId: UUID,
        orderId: String,
        reasonCategory: String? = null,
    ): Envelope<Map<String, Any?>> =
        envelope(
            type,
            paymentId,
            mapOf(
                "paymentId" to paymentId.toString(),
                "orderId" to orderId,
                "status" to type.name.removePrefix("Payment").lowercase(),
                "reasonCategory" to reasonCategory,
            ),
        )

    private fun envelope(
        type: EventType,
        aggregateId: UUID,
        payload: Map<String, Any?>,
    ): Envelope<Map<String, Any?>> = Envelope.of(type, aggregateId, CORRELATION_ID, payload)

    private fun send(envelope: Envelope<*>) {
        kafka
            .send(EventType.topicOf(envelope.type), envelope.aggregateId.toString(), EnvelopeJson.write(envelope))
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
}

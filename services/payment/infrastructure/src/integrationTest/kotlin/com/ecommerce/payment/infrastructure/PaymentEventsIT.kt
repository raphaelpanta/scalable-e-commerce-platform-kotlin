package com.ecommerce.payment.infrastructure

import com.ecommerce.platform.messaging.envelope.EventType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

private val WAIT: Duration = Duration.ofSeconds(30)

/** The consumers of `order.order.v1` (group `payment`): OrderPlaced charges, OrderCancelled refunds. */
class PaymentEventsIT : PaymentIntegrationTest() {
    private fun orderPlaced(charge: Charge): Map<String, Any?> =
        mapOf(
            "orderId" to charge.orderId.toString(),
            "orderNumber" to "ORD-20261002-0001",
            "accountId" to charge.owner.toString(),
            "total" to mapOf("amountMinor" to charge.amountMinor, "currency" to "BRL"),
            "orderStatus" to "placed",
            "paymentStatus" to "pending",
            "paymentMethodRef" to charge.token,
            "idempotencyKey" to charge.key.toString(),
            "recipient" to recipient(charge.owner),
        )

    private fun orderCancelled(
        charge: Charge,
        paymentId: String?,
        paymentStatus: String = "approved",
    ): Map<String, Any?> =
        mapOf(
            "orderId" to charge.orderId.toString(),
            "orderNumber" to "ORD-20261002-0001",
            "accountId" to charge.owner.toString(),
            "lines" to listOf(mapOf("productId" to UUID.randomUUID().toString(), "quantity" to 1)),
            "total" to mapOf("amountMinor" to charge.amountMinor, "currency" to "BRL"),
            "orderStatus" to "cancelled",
            "paymentStatus" to paymentStatus,
            "reason" to "SHOPPER_REQUEST",
            "refundRequired" to (paymentStatus == "approved"),
            "paymentId" to paymentId,
            "cancelledAt" to "2026-10-02T10:45:00Z",
            "recipient" to recipient(charge.owner),
        )

    private fun recipient(owner: UUID): Map<String, Any?> =
        mapOf(
            "accountId" to owner.toString(),
            "email" to "ada@example.test",
            "phone" to null,
            "preferredChannels" to listOf("email"),
        )

    private fun attemptsOf(charge: Charge): List<Any?> =
        column("SELECT outcome FROM payment_attempts WHERE order_id = :id", "id" to charge.orderId)

    private fun awaitProcessed(eventId: UUID) {
        await().atMost(WAIT).until {
            column("SELECT consumer FROM processed_event WHERE event_id = :id", "id" to eventId) == listOf("payment")
        }
    }

    @Test
    fun `OrderPlaced charges an order the synchronous call never reached and publishes the outcome`() {
        val charge = Charge()

        publishOrderEvent(EventType.OrderPlaced, charge.orderId, orderPlaced(charge))

        val event =
            recorded.awaitType(EventType.PaymentApproved) {
                it.payload["orderId"].asString() ==
                    "${charge.orderId}"
            }
        event.correlationId shouldBe CORRELATION_ID
        event.payload["idempotencyKey"].asString() shouldBe charge.key.toString()
        attemptsOf(charge) shouldContainExactly listOf("approved")
    }

    @Test
    fun `OrderPlaced converges with the synchronous charge of the same checkout key, delivered twice`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val eventId = UUID.randomUUID()

        publishOrderEvent(EventType.OrderPlaced, charge.orderId, orderPlaced(charge), eventId)
        publishOrderEvent(EventType.OrderPlaced, charge.orderId, orderPlaced(charge), eventId)
        awaitProcessed(eventId)

        attemptsOf(charge) shouldContainExactly listOf("approved")
        column("SELECT event_type FROM outbox WHERE aggregate_id = :id", "id" to attemptId) shouldContainExactly
            listOf("PaymentApproved")
    }

    @Test
    fun `OrderCancelled with an approved payment records one refund and publishes RefundRecorded for the shopper`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val eventId = UUID.randomUUID()

        publishOrderEvent(EventType.OrderCancelled, charge.orderId, orderCancelled(charge, attemptId), eventId)
        publishOrderEvent(EventType.OrderCancelled, charge.orderId, orderCancelled(charge, attemptId), eventId)

        val event = recorded.awaitType(EventType.RefundRecorded) { it.aggregateId.toString() == attemptId }
        awaitProcessed(eventId)
        event.correlationId shouldBe CORRELATION_ID
        event.payload["paymentId"].asString() shouldBe attemptId
        event.payload["orderId"].asString() shouldBe charge.orderId.toString()
        event.payload["amount"]["amountMinor"].asLong() shouldBe charge.amountMinor
        event.payload["providerReference"].asString().startsWith("sim_rf_") shouldBe true
        event.payload["recipient"]["email"].asString() shouldBe "ada@example.test"
        event.payload["recipient"]["phone"].isNull shouldBe true
        column("SELECT count(*) FROM refunds WHERE order_id = :id", "id" to charge.orderId) shouldBe listOf(1L)
        column(
            "SELECT count(*) FROM outbox WHERE event_type = 'RefundRecorded' AND aggregate_id = :id",
            "id" to attemptId,
        ) shouldBe
            listOf(1L)
    }

    @Test
    fun `a refund recorded over HTTP is announced by the OrderCancelled event, once`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val refundId = body(postRefund(charge, attemptId).expectStatus().isCreated)["refundId"].toString()
        column("SELECT event_type FROM outbox WHERE aggregate_id = :id", "id" to attemptId) shouldContainExactly
            listOf("PaymentApproved")

        publishOrderEvent(EventType.OrderCancelled, charge.orderId, orderCancelled(charge, null))

        val event = recorded.awaitType(EventType.RefundRecorded) { it.aggregateId.toString() == attemptId }
        event.payload["refundId"].asString() shouldBe refundId
        column("SELECT count(*) FROM refunds WHERE order_id = :id", "id" to charge.orderId) shouldBe listOf(1L)
    }

    @Test
    fun `OrderCancelled without an approved payment refunds nothing`() {
        val charge = Charge(token = UNREACHABLE_TOKEN)
        charged(charge)
        val eventId = UUID.randomUUID()

        publishOrderEvent(EventType.OrderCancelled, charge.orderId, orderCancelled(charge, null, "failed"), eventId)
        awaitProcessed(eventId)

        column("SELECT count(*) FROM refunds WHERE order_id = :id", "id" to charge.orderId) shouldBe listOf(0L)
    }
}

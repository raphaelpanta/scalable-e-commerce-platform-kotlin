package com.ecommerce.payment.infrastructure

import com.ecommerce.platform.messaging.envelope.EventType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The consumers of `order.order.v1` (group `payment`): OrderPlaced charges, OrderCancelled refunds an approved charge
 * and voids a pending one, and an approval that races a cancellation is refunded whichever comes first.
 */
class PaymentEventsIT : PaymentIntegrationTest() {
    /** The order of [charge] cancelled while its payment was still pending (expired or cancelled by the shopper). */
    private fun cancelledPending(charge: Charge): Map<String, Any?> = orderCancelled(charge, null, "failed")

    private fun refundsOf(charge: Charge): List<Any?> =
        column("SELECT attempt_id FROM refunds WHERE order_id = :id", "id" to charge.orderId)

    private fun refundEventsOf(attemptId: Any?): List<Any?> =
        column(
            "SELECT count(*) FROM outbox WHERE event_type = 'RefundRecorded' AND aggregate_id = :id",
            "id" to checkNotNull(attemptId).toString(),
        )

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
        refundEventsOf(attemptId) shouldBe listOf(1L)
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
    fun `OrderCancelled of an order whose payment is pending voids the attempt, refunds nothing, publishes nothing`() {
        val charge = Charge(token = UNREACHABLE_FOREVER_TOKEN)
        val attemptId = charged(charge)["attemptId"].toString()
        val eventId = UUID.randomUUID()

        publishOrderEvent(EventType.OrderCancelled, charge.orderId, orderCancelled(charge, null, "failed"), eventId)
        awaitProcessed(eventId)

        attemptsOf(charge) shouldContainExactly listOf("voided")
        column("SELECT count(*) FROM refunds WHERE order_id = :id", "id" to charge.orderId) shouldBe listOf(0L)
        column("SELECT event_type FROM outbox WHERE aggregate_id = :id", "id" to attemptId) shouldContainExactly
            listOf("PaymentPending")
        column("SELECT email FROM cancelled_orders WHERE order_id = :id", "id" to charge.orderId) shouldBe
            listOf("ada@example.test")
    }

    @Test
    fun `an OrderPlaced charge approved after the order's cancellation is refunded with RefundRecorded`() {
        val charge = Charge()
        val cancellation = UUID.randomUUID()
        publishOrderEvent(EventType.OrderCancelled, charge.orderId, cancelledPending(charge), cancellation)
        awaitProcessed(cancellation)

        publishOrderEvent(EventType.OrderPlaced, charge.orderId, orderPlaced(charge))

        val order = charge.orderId.toString()
        val refund = recorded.awaitType(EventType.RefundRecorded) { it.payload["orderId"].asString() == order }
        val approved = recorded.awaitType(EventType.PaymentApproved) { it.payload["orderId"].asString() == order }
        refund.payload["paymentId"].asString() shouldBe approved.payload["paymentId"].asString()
        refund.payload["recipient"]["email"].asString() shouldBe "ada@example.test"
        attemptsOf(charge) shouldContainExactly listOf("approved")
        refundsOf(charge) shouldBe listOf(UUID.fromString(approved.payload["paymentId"].asString()))
    }

    @Test
    fun `a charge racing the order's cancellation ends with exactly one refund, whichever wins`() {
        val charges = List(RACES) { Charge() }
        val cancellations =
            charges.mapIndexed { index, charge ->
                val eventId = UUID.randomUUID()
                val cancel = {
                    publishOrderEvent(
                        EventType.OrderCancelled,
                        charge.orderId,
                        cancelledPending(charge),
                        eventId,
                    )
                }
                val pay = { postCharge(charge).expectStatus().isCreated }
                val first = CompletableFuture.runAsync { if (index % 2 == 0) cancel() else pay() }
                if (index % 2 == 0) pay() else cancel()
                first.join()
                eventId
            }
        cancellations.forEach(::awaitProcessed)

        charges.forEach { charge ->
            attemptsOf(charge) shouldContainExactly listOf("approved")
            val refunded = refundsOf(charge)
            refunded.size shouldBe 1
            refundEventsOf(refunded.single()) shouldBe listOf(1L)
        }
    }

    private companion object {
        const val RACES = 6
    }
}

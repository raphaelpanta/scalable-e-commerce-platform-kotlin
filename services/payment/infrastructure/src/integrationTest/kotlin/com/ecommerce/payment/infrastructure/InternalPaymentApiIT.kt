package com.ecommerce.payment.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.util.UUID

private const val DECLINED_AMOUNT = 4_913L
private const val EXPIRED_AMOUNT = 4_914L
private const val BAD_REQUEST = 400
private const val APPROVED_AMOUNT = 4_915L

/** `POST /internal/charges` and `POST /internal/refunds` (payment-internal.yaml) with their outbox events. */
@Suppress("TooManyFunctions") // one test per rule of payment-internal.yaml
class InternalPaymentApiIT : PaymentIntegrationTest() {
    @Test
    fun `an approved charge answers 201 and publishes PaymentApproved keyed by the attempt`() {
        val charge = Charge()

        val attempt = charged(charge)

        attempt["orderId"] shouldBe charge.orderId.toString()
        attempt["kind"] shouldBe "charge"
        attempt["outcome"] shouldBe "approved"
        attempt["providerReference"].toString() shouldStartWith "sim_ch_"
        attempt.containsKey("declineCategory") shouldBe false
        val attemptId = attempt["attemptId"].toString()
        val record = recorded.awaitRecord { it.topic == Topic.PAYMENT && it.key == attemptId }
        record.envelope.type shouldBe EventType.PaymentApproved.name
        record.envelope.correlationId shouldBe CORRELATION_ID
        record.envelope.producer shouldBe "payment"
        val payload = record.envelope.payload
        payload["paymentId"].asString() shouldBe attemptId
        payload["orderId"].asString() shouldBe charge.orderId.toString()
        payload["accountId"].asString() shouldBe charge.owner.toString()
        payload["amount"]["amountMinor"].asLong() shouldBe charge.amountMinor
        payload["status"].asString() shouldBe "approved"
        payload["idempotencyKey"].asString() shouldBe charge.key.toString()
        payload["providerReference"].asString() shouldBe attempt["providerReference"]
        payload.has("reasonCategory") shouldBe false
    }

    @Test
    fun `declined charges carry the category of the simulator rule and publish PaymentDeclined`() {
        listOf(
            Charge(amountMinor = DECLINED_AMOUNT) to "insufficient_funds",
            Charge(amountMinor = EXPIRED_AMOUNT) to "card_expired",
            Charge(token = "tok_sim_decline_0001") to "card_rejected",
        ).forEach { (charge, category) ->
            val attempt = charged(charge)
            attempt["outcome"] shouldBe "declined"
            attempt["declineCategory"] shouldBe category
            val event =
                recorded.awaitType(EventType.PaymentDeclined) {
                    it.aggregateId.toString() == attempt["attemptId"]
                }
            event.payload["reasonCategory"].asString() shouldBe category
            event.payload["status"].asString() shouldBe "declined"
        }
    }

    @Test
    fun `an unreachable provider is a 201 pending without reference and publishes PaymentPending`() {
        val attempt = charged(Charge(token = UNREACHABLE_TOKEN, amountMinor = DECLINED_AMOUNT))

        attempt["outcome"] shouldBe "pending"
        attempt.containsKey("providerReference") shouldBe false
        attempt.containsKey("declineCategory") shouldBe false
        val event = recorded.awaitType(EventType.PaymentPending) { it.aggregateId.toString() == attempt["attemptId"] }
        event.payload["pendingReason"].asString() shouldBe "PROVIDER_UNAVAILABLE"
        event.payload.has("providerReference") shouldBe false
    }

    @Test
    fun `a replay with the same key and body answers 200 with the stored attempt and publishes nothing new`() {
        val charge = Charge()
        val first = charged(charge)
        val attemptId = first["attemptId"].toString()

        val replay = body(postCharge(charge).expectStatus().isOk)

        replay shouldBe first
        column("SELECT count(*) FROM payment_attempts WHERE order_id = :id", "id" to charge.orderId) shouldBe
            listOf(1L)
        column("SELECT event_type FROM outbox WHERE aggregate_id = :id", "id" to attemptId) shouldContainExactly
            listOf("PaymentApproved")
    }

    @Test
    fun `the same key with a different body is 422 and a new key for a paid order is 409`() {
        val charge = Charge()
        charged(charge)

        postCharge(charge, charge.copy(amountMinor = 1).body()).expectProblem(ProblemType.VALIDATION)
        postCharge(charge.copy(key = UUID.randomUUID())).expectProblem(ProblemType.CONFLICT)
        column("SELECT count(*) FROM payment_attempts WHERE order_id = :id", "id" to charge.orderId) shouldBe
            listOf(1L)
    }

    @Test
    fun `a declined order can be charged again under a new key`() {
        val declined = Charge(amountMinor = DECLINED_AMOUNT)
        charged(declined)

        charged(declined.copy(key = UUID.randomUUID(), amountMinor = APPROVED_AMOUNT))["outcome"] shouldBe
            "approved"
    }

    @Test
    fun `internal calls need the internal token, a UUID key and a valid body`() {
        val charge = Charge()
        postCharge(charge, token = null).expectProblem(ProblemType.UNAUTHORIZED)
        postCharge(charge, token = "wrong").expectProblem(ProblemType.UNAUTHORIZED)
        client
            .post()
            .uri(CHARGES)
            .header(
                com.ecommerce.platform.testing.InternalToken.HEADER,
                com.ecommerce.platform.testing.InternalToken.TEST,
            ).bodyValue(charge.body())
            .exchange()
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        val invalid =
            postCharge(
                charge,
                mapOf("orderId" to "x", "amount" to mapOf("amountMinor" to 0, "currency" to "brl"), "extra" to 1),
            ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        val fields = (invalid["errors"] as List<*>).map { (it as Map<*, *>)["field"] }
        fields shouldContainExactly
            listOf("extra", "amount.amountMinor", "amount.currency", "orderId", "accountId", "paymentMethodRef")
        column("SELECT count(*) FROM payment_attempts WHERE order_id = :id", "id" to charge.orderId) shouldBe
            listOf(0L)
    }

    @Test
    fun `an approved charge is refunded once in full, replays answer 200 and other keys 409`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val key = UUID.randomUUID()

        val refund = body(postRefund(charge, attemptId, key).expectStatus().isCreated)

        refund["orderId"] shouldBe charge.orderId.toString()
        refund["attemptId"] shouldBe attemptId
        refund["status"] shouldBe "recorded"
        refund["amount"] shouldBe mapOf("amountMinor" to charge.amountMinor.toInt(), "currency" to "BRL")
        body(postRefund(charge, attemptId, key).expectStatus().isOk) shouldBe refund
        postRefund(charge, attemptId, key, amountMinor = 1).expectProblem(ProblemType.VALIDATION)
        postRefund(charge, attemptId).expectProblem(ProblemType.CONFLICT)
        column("SELECT count(*) FROM refunds WHERE attempt_id = :id", "id" to UUID.fromString(attemptId)) shouldBe
            listOf(1L)
    }

    @Test
    fun `refunds need an approved charge of the order for its full amount`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val declined = Charge(amountMinor = DECLINED_AMOUNT)
        val declinedId = charged(declined)["attemptId"].toString()

        postRefund(charge, UUID.randomUUID().toString()).expectProblem(ProblemType.NOT_FOUND)
        postRefund(declined.copy(orderId = charge.orderId), declinedId).expectProblem(ProblemType.NOT_FOUND)
        postRefund(declined, declinedId).expectProblem(ProblemType.CONFLICT)
        postRefund(charge, attemptId, amountMinor = 1).expectProblem(ProblemType.VALIDATION)
    }
}

package com.ecommerce.order.infrastructure

import arrow.core.left
import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.order.application.AnonymiseAccountOrders
import com.ecommerce.order.application.ApplyPaymentOutcome
import com.ecommerce.order.application.ChargeRequest
import com.ecommerce.order.application.RecordRefund
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.RefundId
import com.ecommerce.order.infrastructure.PactValues.ADA
import com.ecommerce.order.infrastructure.PactValues.ATTEMPT_APPROVED
import com.ecommerce.order.infrastructure.PactValues.ATTEMPT_DECLINED
import com.ecommerce.order.infrastructure.PactValues.ATTEMPT_PENDING
import com.ecommerce.order.infrastructure.PactValues.KEY_1
import com.ecommerce.order.infrastructure.PactValues.KEY_2
import com.ecommerce.order.infrastructure.PactValues.KEY_3
import com.ecommerce.order.infrastructure.PactValues.KEY_REFUND
import com.ecommerce.order.infrastructure.PactValues.ORDER_1
import com.ecommerce.order.infrastructure.PactValues.ORDER_2
import com.ecommerce.order.infrastructure.PactValues.ORDER_3
import com.ecommerce.order.infrastructure.PactValues.REFUND
import com.ecommerce.order.infrastructure.clients.PaymentClient
import com.ecommerce.order.infrastructure.messaging.Handling
import com.ecommerce.order.infrastructure.messaging.OrderEventHandlers
import com.ecommerce.platform.messaging.envelope.Topic
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

private const val CHARGES = "/internal/charges"
private const val REFUNDS = "/internal/refunds"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val APPROVED_TOKEN = "tok_sim_approve_4242"
private const val UNREACHABLE_TOKEN = "tok_sim_unreachable"
private const val TOTAL_1 = 19_800L
private const val TOTAL_2 = 4_900L
private const val TOTAL_3 = 4_913L

private fun charge(
    orderId: String,
    amountMinor: Long,
    token: String,
): DslPart =
    json { body ->
        body.stringValue("orderId", orderId)
        body.stringValue("accountId", ADA)
        body.money("amount", amountMinor)
        body.stringValue("paymentMethodRef", token)
    }

private fun PactDslWithState.charging(
    description: String,
    key: String,
    body: DslPart,
) = internalRequest(description, "POST", CHARGES)
    .headers(IDEMPOTENCY_KEY, key)
    .jsonBody(body)

private val REFUND_BODY: DslPart =
    json { body ->
        body.stringValue("orderId", ORDER_1)
        body.stringValue("attemptId", ATTEMPT_APPROVED)
        body.money("amount", TOTAL_1)
    }

private fun approvedAttempt(
    body: LambdaDslObject,
    exact: Boolean,
) {
    if (exact) {
        body.stringValue(
            "attemptId",
            ATTEMPT_APPROVED,
        )
    } else {
        body.uuid("attemptId", UUID.fromString(ATTEMPT_APPROVED))
    }
    body.stringValue("orderId", ORDER_1)
    body.stringValue("kind", "charge")
    body.stringValue("outcome", "approved")
    if (exact) {
        body.stringValue(
            "providerReference",
            "sim_ch_000123",
        )
    } else {
        body.stringType("providerReference", "sim_ch_000123")
    }
    if (exact) {
        body.stringValue("createdAt", "2026-10-02T10:15:01Z")
    } else {
        body.stringMatcher("createdAt", PactValues.TIMESTAMP_REGEX, "2026-10-02T10:15:01Z")
    }
}

private fun refundRecord(
    body: LambdaDslObject,
    exact: Boolean,
) {
    if (exact) body.stringValue("refundId", REFUND) else body.uuid("refundId", UUID.fromString(REFUND))
    body.stringValue("orderId", ORDER_1)
    body.stringValue("attemptId", ATTEMPT_APPROVED)
    body.money("amount", TOTAL_1)
    body.stringValue("status", "recorded")
    if (exact) {
        body.stringValue("createdAt", "2026-10-02T11:00:00Z")
    } else {
        body.stringMatcher("createdAt", PactValues.TIMESTAMP_REGEX, "2026-10-02T11:00:00Z")
    }
}

private fun paymentState(
    paymentId: String,
    orderId: String,
): Map<String, Any> = mapOf("paymentId" to paymentId, "orderId" to orderId, "accountId" to ADA)

private fun MessagePactBuilder.paymentEvent(
    state: String,
    stateParameters: Map<String, Any>,
    description: String,
    content: DslPart,
    key: String,
): V4Pact =
    given(state, stateParameters)
        .expectsToReceive(description)
        .withMetadata(mapOf("topic" to Topic.PAYMENT, "kafkaKey" to key))
        .withContent(content)
        .toPact(V4Pact::class.java)

/**
 * Consumer side of order -> payment: the charge and refund calls (pact-interactions.md section 2.4) through the real
 * [PaymentClient], and the payment events order consumes (section 3.2) through the real [OrderEventHandlers].
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "payment", pactVersion = PactSpecVersion.V4)
@Suppress("TooManyFunctions") // one pact method and one test per interaction of the tables
class PaymentConsumerPactTest {
    private val applyPaymentOutcome = mockk<ApplyPaymentOutcome>()
    private val recordRefund = mockk<RecordRefund>()
    private val handlers = OrderEventHandlers(applyPaymentOutcome, recordRefund, mockk<AnonymiseAccountOrders>())

    @Pact(consumer = "order")
    fun chargeApproved(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no payment exists for the order", mapOf("orderId" to ORDER_1))
            .charging(
                "a request to charge an order with an approved payment method",
                KEY_1,
                charge(ORDER_1, TOTAL_1, APPROVED_TOKEN),
            ).jsonAnswer(PactValues.CREATED, json { approvedAttempt(it, exact = false) })
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun chargeDeclined(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no payment exists for the order", mapOf("orderId" to ORDER_3))
            .charging(
                "a request to charge an order that the provider declines",
                KEY_3,
                charge(ORDER_3, TOTAL_3, APPROVED_TOKEN),
            ).jsonAnswer(
                PactValues.CREATED,
                json { body ->
                    body.uuid("attemptId", UUID.fromString(ATTEMPT_DECLINED))
                    body.stringValue("orderId", ORDER_3)
                    body.stringValue("kind", "charge")
                    body.stringValue("outcome", "declined")
                    body.stringValue("declineCategory", "insufficient_funds")
                    body.stringType("providerReference", "sim_ch_000124")
                    body.stringMatcher("createdAt", PactValues.TIMESTAMP_REGEX, "2026-10-02T10:30:00Z")
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun chargePending(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no payment exists for the order", mapOf("orderId" to ORDER_2))
            .charging(
                "a request to charge an order while the provider is unreachable",
                KEY_2,
                charge(ORDER_2, TOTAL_2, UNREACHABLE_TOKEN),
            ).jsonAnswer(
                PactValues.CREATED,
                json { body ->
                    body.uuid("attemptId", UUID.fromString(ATTEMPT_PENDING))
                    body.stringValue("orderId", ORDER_2)
                    body.stringValue("kind", "charge")
                    body.stringValue("outcome", "pending")
                    body.stringMatcher("createdAt", PactValues.TIMESTAMP_REGEX, "2026-10-02T10:20:00Z")
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun chargeReplayed(builder: PactDslWithProvider): V4Pact =
        builder
            .given(
                "a charge attempt exists",
                mapOf(
                    "attemptId" to ATTEMPT_APPROVED,
                    "orderId" to ORDER_1,
                    "accountId" to ADA,
                    "idempotencyKey" to KEY_1,
                    "amountMinor" to TOTAL_1,
                    "currency" to "BRL",
                    "paymentMethodRef" to APPROVED_TOKEN,
                    "outcome" to "approved",
                    "providerReference" to "sim_ch_000123",
                    "createdAt" to "2026-10-02T10:15:01Z",
                ),
            ).charging(
                "a request to charge an order again with the same idempotency key",
                KEY_1,
                charge(ORDER_1, TOTAL_1, APPROVED_TOKEN),
            ).jsonAnswer(PactValues.OK, json { approvedAttempt(it, exact = true) })
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun refund(builder: PactDslWithProvider): V4Pact =
        builder
            .given(
                "an approved charge exists",
                mapOf(
                    "attemptId" to ATTEMPT_APPROVED,
                    "orderId" to ORDER_1,
                    "accountId" to ADA,
                    "amountMinor" to TOTAL_1,
                    "currency" to "BRL",
                ),
            ).internalRequest("a request to refund an approved charge", "POST", REFUNDS)
            .headers(IDEMPOTENCY_KEY, KEY_REFUND)
            .jsonBody(REFUND_BODY)
            .jsonAnswer(PactValues.CREATED, json { refundRecord(it, exact = false) })
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun refundReplayed(builder: PactDslWithProvider): V4Pact =
        builder
            .given(
                "a refund exists",
                mapOf(
                    "refundId" to REFUND,
                    "attemptId" to ATTEMPT_APPROVED,
                    "orderId" to ORDER_1,
                    "idempotencyKey" to KEY_REFUND,
                    "amountMinor" to TOTAL_1,
                    "currency" to "BRL",
                    "createdAt" to "2026-10-02T11:00:00Z",
                ),
            ).internalRequest(
                "a request to refund an approved charge again with the same idempotency key",
                "POST",
                REFUNDS,
            ).headers(IDEMPOTENCY_KEY, KEY_REFUND)
            .jsonBody(REFUND_BODY)
            .jsonAnswer(PactValues.OK, json { refundRecord(it, exact = true) })
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun paymentApproved(builder: MessagePactBuilder): V4Pact =
        builder.paymentEvent(
            "a payment was approved",
            paymentState(ATTEMPT_APPROVED, ORDER_1),
            "a PaymentApproved event for an order awaiting payment",
            envelope(
                "ee000012-0000-4000-8000-000000000012",
                "PaymentApproved",
                "2026-10-02T10:15:01Z",
                ATTEMPT_APPROVED,
                "payment",
            ) {
                it.stringValue("paymentId", ATTEMPT_APPROVED)
                it.stringValue("orderId", ORDER_1)
            },
            ATTEMPT_APPROVED,
        )

    @Pact(consumer = "order")
    fun paymentDeclined(builder: MessagePactBuilder): V4Pact =
        builder.paymentEvent(
            "a payment was declined",
            paymentState(ATTEMPT_DECLINED, ORDER_3) + ("reasonCategory" to "insufficient_funds"),
            "a PaymentDeclined event for an order awaiting payment",
            envelope(
                "ee000013-0000-4000-8000-000000000013",
                "PaymentDeclined",
                "2026-10-02T10:30:00Z",
                ATTEMPT_DECLINED,
                "payment",
            ) {
                it.stringValue("paymentId", ATTEMPT_DECLINED)
                it.stringValue("orderId", ORDER_3)
                it.stringValue("reasonCategory", "insufficient_funds")
            },
            ATTEMPT_DECLINED,
        )

    @Pact(consumer = "order")
    fun paymentPending(builder: MessagePactBuilder): V4Pact =
        builder.paymentEvent(
            "a payment is pending",
            paymentState(ATTEMPT_PENDING, ORDER_2),
            "a PaymentPending event for an order awaiting payment",
            envelope(
                "ee000014-0000-4000-8000-000000000014",
                "PaymentPending",
                "2026-10-02T10:20:00Z",
                ATTEMPT_PENDING,
                "payment",
            ) {
                it.stringValue("paymentId", ATTEMPT_PENDING)
                it.stringValue("orderId", ORDER_2)
            },
            ATTEMPT_PENDING,
        )

    @Pact(consumer = "order")
    fun refundRecorded(builder: MessagePactBuilder): V4Pact =
        builder.paymentEvent(
            "a refund was recorded",
            paymentState(ATTEMPT_APPROVED, ORDER_1) + ("refundId" to REFUND),
            "a RefundRecorded event for a cancelled order",
            envelope(
                "ee000015-0000-4000-8000-000000000015",
                "RefundRecorded",
                "2026-10-02T11:00:00Z",
                ATTEMPT_APPROVED,
                "payment",
            ) {
                it.stringValue("refundId", REFUND)
                it.stringValue("paymentId", ATTEMPT_APPROVED)
                it.stringValue("orderId", ORDER_1)
            },
            ATTEMPT_APPROVED,
        )

    @Test
    @PactTestFor(pactMethod = "chargeApproved")
    fun `an approved charge approves the payment`(mockServer: MockServer) {
        charge(mockServer, ORDER_1, TOTAL_1, APPROVED_TOKEN, KEY_1) shouldBe
            PaymentOutcome.Approved(attempt(ATTEMPT_APPROVED))
    }

    @Test
    @PactTestFor(pactMethod = "chargeDeclined")
    fun `a declined charge carries the decline category`(mockServer: MockServer) {
        charge(mockServer, ORDER_3, TOTAL_3, APPROVED_TOKEN, KEY_3) shouldBe
            PaymentOutcome.Declined(attempt(ATTEMPT_DECLINED), DeclineCategory.INSUFFICIENT_FUNDS)
    }

    @Test
    @PactTestFor(pactMethod = "chargePending")
    fun `an unreachable provider leaves the payment pending`(mockServer: MockServer) {
        charge(mockServer, ORDER_2, TOTAL_2, UNREACHABLE_TOKEN, KEY_2) shouldBe
            PaymentOutcome.Pending(attempt(ATTEMPT_PENDING))
    }

    @Test
    @PactTestFor(pactMethod = "chargeReplayed")
    fun `a replayed charge returns the same attempt`(mockServer: MockServer) {
        charge(mockServer, ORDER_1, TOTAL_1, APPROVED_TOKEN, KEY_1) shouldBe
            PaymentOutcome.Approved(attempt(ATTEMPT_APPROVED))
    }

    @Test
    @PactTestFor(pactMethod = "refund")
    fun `an approved charge is refunded`(mockServer: MockServer) {
        refund(mockServer) shouldBe UUID.fromString(REFUND)
    }

    @Test
    @PactTestFor(pactMethod = "refundReplayed")
    fun `a replayed refund returns the same refund`(mockServer: MockServer) {
        refund(mockServer) shouldBe UUID.fromString(REFUND)
    }

    @Test
    @PactTestFor(pactMethod = "paymentApproved", providerType = ProviderType.ASYNCH)
    fun `PaymentApproved approves the order's payment`(pact: V4Pact) {
        coEvery { applyPaymentOutcome(any(), any()) } returns OrderError.OrderNotFound.left()
        withPactCorrelation { handlers.onPaymentEvent(receivedMessage(pact)) } shouldBe Handling.IGNORED
        coVerify { applyPaymentOutcome(order(ORDER_1), PaymentOutcome.Approved(attempt(ATTEMPT_APPROVED))) }
    }

    @Test
    @PactTestFor(pactMethod = "paymentDeclined", providerType = ProviderType.ASYNCH)
    fun `PaymentDeclined fails the order's payment with the category`(pact: V4Pact) {
        coEvery { applyPaymentOutcome(any(), any()) } returns OrderError.OrderNotFound.left()
        withPactCorrelation { handlers.onPaymentEvent(receivedMessage(pact)) }
        coVerify {
            applyPaymentOutcome(
                order(ORDER_3),
                PaymentOutcome.Declined(attempt(ATTEMPT_DECLINED), DeclineCategory.INSUFFICIENT_FUNDS),
            )
        }
    }

    @Test
    @PactTestFor(pactMethod = "paymentPending", providerType = ProviderType.ASYNCH)
    fun `PaymentPending notes the attempt`(pact: V4Pact) {
        coEvery { applyPaymentOutcome(any(), any()) } returns OrderError.OrderNotFound.left()
        withPactCorrelation { handlers.onPaymentEvent(receivedMessage(pact)) }
        coVerify { applyPaymentOutcome(order(ORDER_2), PaymentOutcome.Pending(attempt(ATTEMPT_PENDING))) }
    }

    @Test
    @PactTestFor(pactMethod = "refundRecorded", providerType = ProviderType.ASYNCH)
    fun `RefundRecorded records the refund of the order`(pact: V4Pact) {
        coEvery { recordRefund(any(), any()) } returns OrderError.OrderNotFound.left()
        withPactCorrelation { handlers.onPaymentEvent(receivedMessage(pact)) }
        coVerify { recordRefund(order(ORDER_1), RefundId(UUID.fromString(REFUND))) }
    }

    @Suppress("LongParameterList") // one charge of the table, field by field
    private fun charge(
        mockServer: MockServer,
        orderId: String,
        amountMinor: Long,
        token: String,
        key: String,
    ): PaymentOutcome =
        withPactCorrelation {
            PaymentClient(internalClient(mockServer)).charge(
                ChargeRequest(
                    order(orderId),
                    AccountId(UUID.fromString(ADA)),
                    Money(amountMinor, "BRL"),
                    token,
                    IdempotencyKey(UUID.fromString(key)),
                ),
            )
        }

    private fun refund(mockServer: MockServer): UUID? =
        withPactCorrelation {
            PaymentClient(internalClient(mockServer)).refund(
                order(ORDER_1),
                attempt(ATTEMPT_APPROVED),
                Money(TOTAL_1, "BRL"),
                IdempotencyKey(UUID.fromString(KEY_REFUND)),
            )
        }

    private fun order(id: String) = OrderId(UUID.fromString(id))

    private fun attempt(id: String) = PaymentAttemptId(UUID.fromString(id))
}

package com.ecommerce.payment.infrastructure

import arrow.core.getOrElse
import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.ProviderReference
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import java.time.Instant
import java.util.UUID

/**
 * The fixed values of contracts/internal/pact-interactions.md section 1 and the fixture payments they describe, as
 * real domain objects: the provider states store them, the message verification publishes them.
 */
object PactFixtures {
    const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
    const val CORRELATION_REGEX = "^[A-Za-z0-9-]{1,64}$"
    const val TIMESTAMP_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$"
    const val ADA = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
    const val ORDER_1 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"
    const val ORDER_2 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11"
    const val ORDER_3 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"
    const val ATTEMPT_APPROVED = "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"
    const val ATTEMPT_PENDING = "d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60"
    const val ATTEMPT_DECLINED = "8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e"
    const val KEY_1 = "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    const val KEY_2 = "3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92"
    const val KEY_3 = "2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81"
    const val KEY_REFUND = "9c8b7a69-5847-4362-9d1e-0f1a2b3c4d5e"
    const val REFUND = "4d5e6f70-8192-4a3b-8c4d-5e6f70819203"
    const val APPROVE_TOKEN = "tok_sim_approve_4242"
    const val TOTAL_1 = 19_800L
    const val TOTAL_2 = 4_900L
    const val TOTAL_3 = 4_913L

    /** Example envelope number 5 of pact-interactions.md section 3.1 (`OrderPlaced`). */
    const val ORDER_PLACED_EVENT = 5

    /** Example envelope number 10 (`OrderCancelled#shopper`). */
    const val ORDER_CANCELLED_EVENT = 10

    val ADA_CONTACT = Recipient(AccountId(UUID.fromString(ADA)), "ada@example.test", null, listOf("email"))

    /** The approved charge of order 1. */
    val approvedCharge: PaymentAttempt =
        attempt(
            ATTEMPT_APPROVED,
            ORDER_1,
            TOTAL_1,
            KEY_1,
            PaymentOutcome.APPROVED,
            "sim_ch_000123",
            "2026-10-02T10:15:01Z",
        )

    /** The declined charge of order 3 (amount ending in 13). */
    val declinedCharge: PaymentAttempt =
        attempt(
            ATTEMPT_DECLINED,
            ORDER_3,
            TOTAL_3,
            KEY_3,
            PaymentOutcome.DECLINED,
            "sim_ch_000124",
            "2026-10-02T10:30:00Z",
        )

    /** The declined charge as the storefront reads it on order 1 (pact-matrix.md P2). */
    val declinedChargeOfOrder1: PaymentAttempt = declinedCharge.copy(orderId = OrderId(UUID.fromString(ORDER_1)))

    /** The pending charge of order 2 (provider unreachable). */
    val pendingCharge: PaymentAttempt =
        attempt(ATTEMPT_PENDING, ORDER_2, TOTAL_2, KEY_2, PaymentOutcome.PENDING, null, "2026-10-02T10:20:00Z")

    /** The refund of the approved charge of order 1. */
    val refund: RefundRecord =
        RefundRecord(
            RefundId(UUID.fromString(REFUND)),
            OrderId(UUID.fromString(ORDER_1)),
            AccountId(UUID.fromString(ADA)),
            PaymentAttemptId(UUID.fromString(ATTEMPT_APPROVED)),
            brl(TOTAL_1),
            ProviderReference("sim_rf_000045"),
            IdempotencyKey(UUID.fromString(KEY_REFUND)),
            Instant.parse("2026-10-02T11:00:00Z"),
            Instant.parse("2026-10-02T11:00:00Z"),
        )

    fun brl(amountMinor: Long): Money = Money(amountMinor, "BRL")

    fun token(value: String): PaymentMethodRef = PaymentMethodRef.of(value).getOrElse { error(it) }

    @Suppress("LongParameterList") // one value per column of the fixture table
    fun attempt(
        id: String,
        orderId: String,
        amountMinor: Long,
        key: String,
        outcome: PaymentOutcome,
        reference: String?,
        createdAt: String,
        accountId: String = ADA,
        paymentMethodRef: String = APPROVE_TOKEN,
    ): PaymentAttempt =
        PaymentAttempt(
            id = PaymentAttemptId(UUID.fromString(id)),
            orderId = OrderId(UUID.fromString(orderId)),
            accountId = AccountId(UUID.fromString(accountId)),
            amount = brl(amountMinor),
            paymentMethodRef = token(paymentMethodRef),
            outcome = outcome,
            declineCategory = DeclineCategory.INSUFFICIENT_FUNDS.takeIf { outcome == PaymentOutcome.DECLINED },
            providerReference = reference?.let(::ProviderReference),
            idempotencyKey = IdempotencyKey(UUID.fromString(key)),
            createdAt = Instant.parse(createdAt),
        )

    /** The event id `ee0000NN-0000-4000-8000-0000000000NN` of example [number]. */
    fun eventId(number: Int): UUID {
        val nn = number.toString().padStart(2, '0')
        return UUID.fromString("ee0000$nn-0000-4000-8000-0000000000$nn")
    }

    /** One message interaction: [description], provider [state] with [parameters], [body] on [topic] keyed [key]. */
    @Suppress("LongParameterList") // one value per column of the pact-interactions.md tables
    fun message(
        builder: PactBuilder,
        description: String,
        state: String,
        parameters: Map<String, Any>,
        topic: String,
        key: String,
        body: DslPart,
    ): V4Pact =
        builder
            .expectsToReceiveMessageInteraction(description) { interaction ->
                interaction.state(state, parameters).withContents { contents ->
                    contents.withMetadata(mapOf("topic" to topic, "kafkaKey" to key)).withContent(body)
                }
            }.toPact()

    /** The envelope of example [number] around [payload]: global matching rules of pact-interactions.md 3. */
    @Suppress("LongParameterList") // the envelope members of events.yaml
    fun envelope(
        number: Int,
        type: String,
        occurredAt: String,
        aggregateId: String,
        producer: String,
        payload: (LambdaDslObject) -> Unit,
    ): DslPart =
        newJsonBody { envelope ->
            envelope.uuid("eventId", eventId(number))
            envelope.stringValue("type", type)
            envelope.numberValue("version", 1)
            envelope.stringMatcher("occurredAt", TIMESTAMP_REGEX, occurredAt)
            envelope.stringValue("aggregateId", aggregateId)
            envelope.stringMatcher("correlationId", CORRELATION_REGEX, CORRELATION_ID)
            envelope.stringValue("producer", producer)
            envelope.`object`("payload") { payload(it) }
        }.build()

    /** `{"amountMinor": .., "currency": "BRL"}` under [name], exact. */
    fun money(
        payload: LambdaDslObject,
        name: String,
        amountMinor: Long,
    ) {
        payload.`object`(name) { money ->
            money.numberValue("amountMinor", amountMinor)
            money.stringValue("currency", "BRL")
        }
    }

    /** The body of a received message. */
    fun bodyOf(message: V4Interaction.AsynchronousMessage): String =
        checkNotNull(message.contents.contents.valueAsString()) { "the message has no body" }
}

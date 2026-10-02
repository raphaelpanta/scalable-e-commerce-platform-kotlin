package com.ecommerce.notification.infrastructure

import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import java.util.UUID

/**
 * Builders of the message pacts of contracts/internal/pact-interactions.md §3: the example envelopes of §3.1 with
 * the global matching rules (`eventId` uuid, timestamps ISO-8601 UTC, `correlationId` regex; `type`, `version`,
 * `producer`, `aggregateId` exact), reduced to the payload members this service reads (minimal pacts).
 */
object PactMessages {
    const val CONSUMER = "notification"
    const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
    const val CORRELATION_REGEX = "^[A-Za-z0-9-]{1,64}$"
    const val TIMESTAMP_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$"
    const val ADA = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
    const val ORDER_PAID = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"
    const val ORDER_DECLINED = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"
    const val PAYMENT_APPROVED = "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"
    const val REFUND = "4d5e6f70-8192-4a3b-8c4d-5e6f70819203"
    const val OPERATOR = "e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"

    /** Example envelope number 1 of pact-interactions.md §3.1. */
    const val ACCOUNT_REGISTERED = 1

    /** Example envelope number 2 of pact-interactions.md §3.1. */
    const val ACCOUNT_VERIFIED = 2

    /** Example envelope number 3 of pact-interactions.md §3.1. */
    const val PASSWORD_RESET_REQUESTED = 3

    /** Example envelope number 4 of pact-interactions.md §3.1. */
    const val ACCOUNT_DELETED = 4

    /** Example envelope number 6 of pact-interactions.md §3.1. */
    const val ORDER_PAID_EVENT = 6

    /** Example envelope number 7 of pact-interactions.md §3.1. */
    const val ORDER_PAYMENT_FAILED = 7

    /** Example envelope number 8 of pact-interactions.md §3.1. */
    const val ORDER_SHIPPED = 8

    /** Example envelope number 9 of pact-interactions.md §3.1. */
    const val ORDER_DELIVERED = 9

    /** Example envelope number 10 of pact-interactions.md §3.1. */
    const val ORDER_CANCELLED = 10

    /** Example envelope number 15 of pact-interactions.md §3.1. */
    const val REFUND_RECORDED = 15

    /** The event id `ee0000NN-0000-4000-8000-0000000000NN` of example [number]. */
    fun eventId(number: Int): UUID {
        val nn = number.toString().padStart(2, '0')
        return UUID.fromString("ee0000$nn-0000-4000-8000-0000000000$nn")
    }

    /** One message interaction of [builder]: [description], provider [state] with [parameters], [body]. */
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

    /** The envelope of example [number] around [payload]. */
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

    /** Ada's `RecipientSnapshot` (email only). */
    fun recipient(payload: LambdaDslObject) {
        payload.`object`("recipient") { recipient ->
            recipient.stringValue("email", "ada@example.test")
            recipient.nullValue("phone")
            recipient.array("preferredChannels") { it.stringValue("email") }
        }
    }

    /** A `Money` member [name] of [amountMinor] BRL. */
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

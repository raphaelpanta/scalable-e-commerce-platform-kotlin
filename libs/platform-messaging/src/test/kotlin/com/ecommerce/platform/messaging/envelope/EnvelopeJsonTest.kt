package com.ecommerce.platform.messaging.envelope

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.javaInstant
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

private data class Money(
    val amountMinor: Long,
    val currency: String,
)

private data class OrderPlacedPayload(
    val orderId: UUID,
    val total: Money,
    val lines: List<String>,
)

private data class AccountOnly(
    val accountId: UUID,
)

private const val PACT_EXAMPLE = """
{
  "eventId": "ee000001-0000-4000-8000-000000000001",
  "type": "AccountRegistered",
  "version": 1,
  "occurredAt": "2026-10-02T10:00:00Z",
  "aggregateId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "identity",
  "payload": {
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "recipient": {"email": "ada@example.test", "phone": null, "preferredChannels": ["email"]},
    "verificationToken": "pact-example-verification-token"
  }
}
"""

private val ORDER_ID: UUID = UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")

private fun orderPlaced(
    payload: OrderPlacedPayload,
    occurredAt: Instant = Instant.parse("2026-10-02T10:00:00Z"),
): Envelope<OrderPlacedPayload> = Envelope.of(EventType.OrderPlaced, ORDER_ID, "corr-1", payload, occurredAt)

class EnvelopeJsonTest :
    FunSpec({
        val payload = OrderPlacedPayload(ORDER_ID, Money(1999, "BRL"), listOf("a", "b"))

        test("an envelope survives a write and read round trip and its payload converts back") {
            val envelope = orderPlaced(payload)

            val received = EnvelopeJson.read(EnvelopeJson.write(envelope))

            received.eventId shouldBe envelope.eventId
            received.type shouldBe "OrderPlaced"
            received.version shouldBe 1
            received.occurredAt shouldBe envelope.occurredAt
            received.aggregateId shouldBe ORDER_ID
            received.correlationId shouldBe "corr-1"
            received.producer shouldBe "order"
            received.payloadAs<OrderPlacedPayload>() shouldBe payload
            received.eventType() shouldBe EventType.OrderPlaced
        }

        test("every envelope round trips for any ids, instants, amounts and types") {
            checkAll(
                Arb.uuid(),
                Arb.enum<EventType>(),
                Arb.javaInstant(Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z")),
                Arb.long(0L..Long.MAX_VALUE),
                Arb.string(1..64, Codepoint.alphanumeric()),
            ) { aggregateId, type, occurredAt, amount, correlationId ->
                val envelope =
                    Envelope.of(type, aggregateId, correlationId, Money(amount, "BRL"), occurredAt)

                val received = EnvelopeJson.read(EnvelopeJson.write(envelope))

                EnvelopeJson.write(received) shouldBe EnvelopeJson.write(envelope)
                received.payloadAs<Money>() shouldBe envelope.payload
                received.eventId shouldBe envelope.eventId
            }
        }

        test("instants are written as ISO-8601 UTC with Z and ids as strings") {
            val json = EnvelopeJson.write(orderPlaced(payload))

            json shouldContain "\"occurredAt\":\"2026-10-02T10:00:00Z\""
            json shouldContain "\"aggregateId\":\"$ORDER_ID\""
            json shouldContain "\"version\":1"
            json shouldNotContain "eventType"
        }

        test("occurredAt is kept with microsecond precision, the precision of PostgreSQL") {
            val precise = Instant.parse("2026-10-02T10:00:00.123456789Z")

            orderPlaced(payload, precise).occurredAt shouldBe precise.truncatedTo(ChronoUnit.MICROS)
        }

        test("the pact example envelope is readable and unknown fields are ignored") {
            val withExtra = PACT_EXAMPLE.replaceFirst("{", "{\"addedInV1_1\": true,")

            val received = EnvelopeJson.read(withExtra)

            received.eventId shouldBe UUID.fromString("ee000001-0000-4000-8000-000000000001")
            received.occurredAt shouldBe Instant.parse("2026-10-02T10:00:00Z")
            received.payload["verificationToken"].asString() shouldBe "pact-example-verification-token"
            received.eventType() shouldBe EventType.AccountRegistered
        }

        test("a payload field the consumer does not know is ignored on conversion") {
            val received = EnvelopeJson.read(PACT_EXAMPLE)

            received.payloadAs<AccountOnly>() shouldBe
                AccountOnly(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"))
        }

        test("text that is not an envelope is rejected as malformed") {
            listOf(
                "not json",
                "null",
                "{}",
                PACT_EXAMPLE.replace("\"version\": 1", "\"version\": 0"),
                PACT_EXAMPLE.replace("3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13", "x".repeat(65)),
                PACT_EXAMPLE.replace("ee000001-0000-4000-8000-000000000001", "not-a-uuid"),
            ).forEach { json ->
                shouldThrow<MalformedEnvelopeException> { EnvelopeJson.read(json) }
            }
        }

        test("an envelope refuses a blank type, producer or correlation id and a version below 1") {
            val valid = orderPlaced(payload)

            shouldThrow<IllegalArgumentException> { valid.copy(type = " ") }
            shouldThrow<IllegalArgumentException> { valid.copy(producer = "") }
            shouldThrow<IllegalArgumentException> { valid.copy(correlationId = "") }
            shouldThrow<IllegalArgumentException> { valid.copy(correlationId = "c".repeat(65)) }
            shouldThrow<IllegalArgumentException> { valid.copy(version = 0) }
            valid.copy(correlationId = "c".repeat(64)).correlationId.length shouldBe 64
        }

        test("the envelope factory stamps the configured producer and clock") {
            val now = Instant.parse("2026-10-02T12:00:00Z")
            val factory = EnvelopeFactory("catalog", Clock.fixed(now, ZoneOffset.UTC))

            val envelope = factory.create(EventType.StockReserved, ORDER_ID, "corr-2", mapOf("orderId" to "x"))

            envelope.producer shouldBe "catalog"
            envelope.occurredAt shouldBe now
            envelope.type shouldBe "StockReserved"
            factory.producer shouldBe "catalog"
        }
    })

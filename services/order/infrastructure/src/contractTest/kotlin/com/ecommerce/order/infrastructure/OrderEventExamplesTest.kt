package com.ecommerce.order.infrastructure

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The order events the provider verification produces equal the example envelopes of pact-interactions.md
 * section 3.1 (copied to `examples/`), apart from the `eventId`, which is fresh for every event.
 */
class OrderEventExamplesTest {
    @Test
    fun `OrderPlaced matches its example`() = matches("OrderPlaced", OrderEventExamples.orderPlaced())

    @Test
    fun `OrderPaid matches its example`() = matches("OrderPaid", OrderEventExamples.orderPaid())

    @Test
    fun `OrderPaymentFailed matches its example`() =
        matches("OrderPaymentFailed", OrderEventExamples.orderPaymentFailed())

    @Test
    fun `OrderShipped matches its example`() = matches("OrderShipped", OrderEventExamples.orderShipped())

    @Test
    fun `OrderDelivered matches its example`() = matches("OrderDelivered", OrderEventExamples.orderDelivered())

    @Test
    fun `OrderCancelled by the shopper matches its example`() =
        matches("OrderCancelled-shopper", OrderEventExamples.orderCancelledByShopper())

    @Test
    fun `OrderCancelled after the payment expired matches its example`() =
        matches("OrderCancelled-expired", OrderEventExamples.orderCancelledAfterExpiry())

    private fun matches(
        example: String,
        produced: String,
    ) {
        val expected = tree(checkNotNull(javaClass.getResource("/examples/$example.json")).readText())
        val actual = tree(produced)
        actual.get("eventId") shouldNotBe null
        withoutEventId(actual) shouldBe withoutEventId(expected)
    }

    private fun tree(json: String): JsonNode = EnvelopeJson.mapper.readTree(json)

    private fun withoutEventId(node: JsonNode): JsonNode = (node.deepCopy() as ObjectNode).apply { remove("eventId") }
}

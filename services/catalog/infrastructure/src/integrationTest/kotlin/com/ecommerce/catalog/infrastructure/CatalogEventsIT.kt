package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.RecordedEvents
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val EVENT_TIMEOUT: Duration = Duration.ofSeconds(30)
private const val SEND_TIMEOUT_SECONDS = 10L
private const val CREATED = 201
private const val NO_CONTENT = 204
private const val STOCK = 5
private const val UNITS = 2

/**
 * The order-event consumers (group `catalog`, ADR 0002 safety net) over Kafka: `OrderPaid` commits,
 * `OrderPaymentFailed` releases, `OrderCancelled` releases or restocks; each effective once per `eventId`, tolerant
 * of reservations the synchronous path already ended, and publishing the stock event with the order's correlation id.
 */
class CatalogEventsIT(
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val recorded: RecordedEvents,
) : CatalogIntegrationTest() {
    private fun send(envelope: Envelope<*>) {
        kafka
            .send(EventType.valueOf(envelope.type).topic, envelope.aggregateId.toString(), EnvelopeJson.write(envelope))
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun orderEvent(
        type: EventType,
        orderId: UUID,
        vararg extra: Pair<String, Any>,
    ): Envelope<Map<String, Any>> =
        EnvelopeFixtures.envelope(type, mapOf("orderId" to orderId.toString(), *extra), aggregateId = orderId)

    /** A new product with a reservation of [UNITS] units for a new order: the product and order ids. */
    private fun reserved(): Pair<String, UUID> {
        val productId = product(stock = STOCK)
        val orderId = UUID.randomUUID()
        val body = mapOf("orderId" to orderId, "lines" to listOf(mapOf("productId" to productId, "quantity" to UNITS)))
        internal(HttpMethod.POST, "/internal/reservations", body).expectStatus().isEqualTo(CREATED)
        return productId to orderId
    }

    private fun awaitStock(
        productId: String,
        expected: String,
    ) {
        await().atMost(EVENT_TIMEOUT).until { stockOf(productId) == expected }
    }

    @Test
    fun `an OrderPaid event delivered twice commits the reservation once and publishes one StockCommitted`() {
        val (productId, orderId) = reserved()
        val paid = orderEvent(EventType.OrderPaid, orderId)

        send(paid)
        send(paid)
        awaitStock(productId, "3/0")

        val committed =
            recorded.awaitType(EventType.StockCommitted) {
                it.payload["orderId"].asString() ==
                    orderId.toString()
            }
        committed.correlationId shouldBe EnvelopeFixtures.CORRELATION_ID
        // A later event of the same order (same partition) proves both deliveries were handled.
        send(orderEvent(EventType.OrderPaid, orderId))
        await().atMost(EVENT_TIMEOUT).until { processed(orderId) == 2L }
        stockOf(productId) shouldBe "3/0"
        recorded.ofType(EventType.StockCommitted).filter {
            it.payload["orderId"].asString() == orderId.toString()
        } shouldHaveSize
            1
    }

    @Test
    fun `an OrderPaymentFailed event releases the reservation with reason PAYMENT_FAILED`() {
        val (productId, orderId) = reserved()

        send(orderEvent(EventType.OrderPaymentFailed, orderId))

        awaitStock(productId, "5/0")
        val released =
            recorded.awaitType(EventType.StockReservationReleased) {
                it.payload["orderId"].asString() ==
                    orderId.toString()
            }
        released.payload["reason"].asString() shouldBe "PAYMENT_FAILED"
    }

    @Test
    fun `an OrderCancelled event restocks a committed reservation and leaves a released one untouched`() {
        val (committedProduct, committedOrder) = reserved()
        val reservationId = reservationOf(committedOrder)
        internal(HttpMethod.POST, "/internal/reservations/$reservationId/commit").expectStatus().isEqualTo(NO_CONTENT)
        val (releasedProduct, releasedOrder) = reserved()
        internal(HttpMethod.POST, "/internal/reservations/${reservationOf(releasedOrder)}/release")
            .expectStatus()
            .isEqualTo(NO_CONTENT)

        send(orderEvent(EventType.OrderCancelled, committedOrder, "reason" to "SHOPPER_REQUEST"))
        send(orderEvent(EventType.OrderCancelled, releasedOrder, "reason" to "PAYMENT_EXPIRED"))

        awaitStock(committedProduct, "5/0")
        val restocked =
            recorded.awaitType(EventType.StockReservationReleased) {
                it.payload["orderId"].asString() ==
                    committedOrder.toString()
            }
        restocked.payload["reason"].asString() shouldBe "CANCELLED"
        restocked.payload["restocked"].asBoolean() shouldBe true
        await().atMost(EVENT_TIMEOUT).until { processed(releasedOrder) == 1L }
        stockOf(releasedProduct) shouldBe "5/0"
        recorded
            .ofType(EventType.StockReservationReleased)
            .filter { it.payload["orderId"].asString() == releasedOrder.toString() } shouldHaveSize 1
    }

    @Test
    fun `an expired order releases its reservation with reason EXPIRED, events of other orders are ignored`() {
        val (productId, orderId) = reserved()

        send(orderEvent(EventType.OrderPlaced, orderId))
        send(orderEvent(EventType.OrderCancelled, UUID.randomUUID(), "reason" to "PAYMENT_EXPIRED"))
        send(orderEvent(EventType.OrderCancelled, orderId, "reason" to "PAYMENT_EXPIRED"))

        awaitStock(productId, "5/0")
        val released =
            recorded.awaitType(EventType.StockReservationReleased) {
                it.payload["orderId"].asString() ==
                    orderId.toString()
            }
        released.payload["reason"].asString() shouldBe "EXPIRED"
    }

    private fun reservationOf(orderId: UUID): UUID =
        checkNotNull(
            database
                .sql("SELECT id FROM reservations WHERE order_id = :orderId")
                .bind("orderId", orderId)
                .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
                .one()
                .block(Duration.ofSeconds(SEND_TIMEOUT_SECONDS)),
        )

    /** Events of [orderId] the `catalog` consumer recorded as processed (the fixtures key events by order id). */
    private fun processed(orderId: UUID): Long =
        recorded.all
            .filter { it.key == orderId.toString() && it.topic == EventType.OrderPaid.topic }
            .map { it.envelope.eventId }
            .distinct()
            .count { eventId ->
                database
                    .sql("SELECT count(*) AS n FROM processed_event WHERE event_id = :id AND consumer = 'catalog'")
                    .bind("id", eventId)
                    .map { row -> row.get("n", Long::class.javaObjectType) ?: 0L }
                    .one()
                    .block(Duration.ofSeconds(SEND_TIMEOUT_SECONDS)) == 1L
            }.toLong()
}

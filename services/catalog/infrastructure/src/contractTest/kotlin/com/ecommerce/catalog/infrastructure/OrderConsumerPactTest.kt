package com.ecommerce.catalog.infrastructure

import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.catalog.infrastructure.messaging.CatalogEventListeners
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.testing.PostgresTestConfig
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.kafka.support.Acknowledgment
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val CONSUMER = "catalog"
private const val PROVIDER = "order"
private const val ADA = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
private const val PAID_ORDER = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"
private const val EXPIRED_ORDER = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11"
private const val DECLINED_ORDER = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"
private const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
private const val CORRELATION_REGEX = "^[A-Za-z0-9-]{1,64}$"
private const val TIMESTAMP_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$"
private const val ON_HAND = 5
private const val UNITS = 2
private val TIMEOUT: Duration = Duration.ofSeconds(10)
private val EXPIRES_AT: Instant = Instant.parse("2099-01-01T00:00:00Z")

/**
 * Consumer `catalog`, provider `order` (pact-interactions.md section 3.2): the order events catalog reads, reduced
 * to what it reads (the order id, and the reason of a cancellation). Each message is delivered twice to the real
 * Kafka listener: the reservation of the order moves once and one stock event is stored in the outbox (FR-022).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V4, providerType = ProviderType.ASYNCH)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class)
@Suppress("TooManyFunctions") // one pact method and one test per row of pact-interactions.md, plus fixtures
class OrderConsumerPactTest {
    @Autowired
    lateinit var listener: CatalogEventListeners

    @Autowired
    lateinit var database: DatabaseClient

    private val product: UUID = UUID.fromString("9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01")

    @BeforeEach
    fun reset() {
        execute(
            "TRUNCATE reservation_lines, reservations, stock_adjustments, product_images, inventory_levels, " +
                "products, categories, outbox, processed_event",
        )
        val category = UUID.randomUUID()
        execute(
            "INSERT INTO categories (id, name, created_at, updated_at) VALUES (:id, 'Kitchen', now(), now())",
            mapOf("id" to category),
        )
        execute(
            "INSERT INTO products (id, sku, name, price_minor, currency, category_id, sale_state, created_at, " +
                "updated_at) VALUES (:id, 'ESP-MACH-01', 'Espresso Machine', 14900, 'BRL', :category, 'active', " +
                "now(), now())",
            mapOf("id" to product, "category" to category),
        )
        execute(
            "INSERT INTO inventory_levels (product_id, on_hand, reserved) VALUES (:id, $ON_HAND, 0)",
            mapOf("id" to product),
        )
    }

    @Pact(consumer = CONSUMER)
    fun orderPaid(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderPaid event that commits the reservation",
            "an order was paid",
            mapOf("orderId" to PAID_ORDER, "accountId" to ADA),
            PAID_ORDER,
            envelope("ee000006-0000-4000-8000-000000000006", "OrderPaid", "2026-10-02T10:15:01Z", PAID_ORDER),
        )

    @Pact(consumer = CONSUMER)
    fun orderPaymentFailed(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderPaymentFailed event that releases the reservation",
            "an order payment failed",
            mapOf("orderId" to DECLINED_ORDER, "accountId" to ADA, "reasonCategory" to "insufficient_funds"),
            DECLINED_ORDER,
            envelope(
                "ee000007-0000-4000-8000-000000000007",
                "OrderPaymentFailed",
                "2026-10-02T10:30:00Z",
                DECLINED_ORDER,
            ),
        )

    @Pact(consumer = CONSUMER)
    fun orderExpired(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderCancelled event after the payment expired",
            "an order was cancelled",
            mapOf(
                "orderId" to EXPIRED_ORDER,
                "accountId" to ADA,
                "reason" to "PAYMENT_EXPIRED",
                "paymentStatus" to "failed",
            ),
            EXPIRED_ORDER,
            envelope("ee000011-0000-4000-8000-000000000011", "OrderCancelled", "2026-10-02T10:50:00Z", EXPIRED_ORDER) {
                it.stringValue("reason", "PAYMENT_EXPIRED")
            },
        )

    @Test
    @PactTestFor(pactMethod = "orderPaid")
    fun `an OrderPaid event twice commits the reservation once and stores one StockCommitted`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        reserve(PAID_ORDER)

        deliverTwice(PAID_ORDER, message)

        stock() shouldBe "${ON_HAND - UNITS}/0"
        stateOf(PAID_ORDER) shouldBe "committed"
        outbox("StockCommitted") shouldBe 1L
    }

    @Test
    @PactTestFor(pactMethod = "orderPaymentFailed")
    fun `an OrderPaymentFailed event twice releases the reservation once and stores one StockReservationReleased`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        reserve(DECLINED_ORDER)

        deliverTwice(DECLINED_ORDER, message)

        stock() shouldBe "$ON_HAND/0"
        stateOf(DECLINED_ORDER) shouldBe "released"
        outbox("StockReservationReleased") shouldBe 1L
    }

    @Test
    @PactTestFor(pactMethod = "orderExpired")
    fun `an OrderCancelled event twice releases once and leaves an already released reservation untouched`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        reserve(EXPIRED_ORDER)

        deliverTwice(EXPIRED_ORDER, message)

        stock() shouldBe "$ON_HAND/0"
        stateOf(EXPIRED_ORDER) shouldBe "released"
        outbox("StockReservationReleased") shouldBe 1L
        execute("DELETE FROM processed_event")
        deliverTwice(EXPIRED_ORDER, message)
        outbox("StockReservationReleased") shouldBe 1L
        stock() shouldBe "$ON_HAND/0"
    }

    private fun deliverTwice(
        orderId: String,
        message: V4Interaction.AsynchronousMessage,
    ) {
        val body = checkNotNull(message.contents.contents.valueAsString()) { "the message has no body" }
        repeat(2) { listener.onOrderEvent(ConsumerRecord(Topic.ORDER, 0, 0L, orderId, body), Acknowledgment { }) }
    }

    /** A reservation of [UNITS] units of the product for [orderId], with the stock it holds. */
    private fun reserve(orderId: String) {
        val reservationId = UUID.randomUUID()
        execute(
            "INSERT INTO reservations (id, order_id, state, created_at, expires_at) " +
                "VALUES (:id, :orderId, 'reserved', now(), :expiresAt)",
            mapOf("id" to reservationId, "orderId" to UUID.fromString(orderId), "expiresAt" to EXPIRES_AT),
        )
        execute(
            "INSERT INTO reservation_lines (reservation_id, product_id, quantity, position) " +
                "VALUES (:id, :product, $UNITS, 0)",
            mapOf("id" to reservationId, "product" to product),
        )
        execute("UPDATE inventory_levels SET reserved = $UNITS WHERE product_id = :id", mapOf("id" to product))
    }

    private fun stock(): String? =
        database
            .sql("SELECT on_hand, reserved FROM inventory_levels WHERE product_id = :id")
            .bind("id", product)
            .map { row ->
                "${row.get("on_hand", Int::class.javaObjectType)}/${row.get("reserved", Int::class.javaObjectType)}"
            }.one()
            .block(TIMEOUT)

    private fun stateOf(orderId: String): String? =
        database
            .sql("SELECT state FROM reservations WHERE order_id = :orderId")
            .bind("orderId", UUID.fromString(orderId))
            .map { row -> row.get("state", String::class.java).orEmpty() }
            .one()
            .block(TIMEOUT)

    private fun outbox(type: String): Long? =
        database
            .sql("SELECT count(*) AS n FROM outbox WHERE event_type = :type")
            .bind("type", type)
            .map { row -> row.get("n", Long::class.javaObjectType) ?: 0L }
            .one()
            .block(TIMEOUT)

    private fun execute(
        sql: String,
        bindings: Map<String, Any> = emptyMap(),
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .fetch()
            .rowsUpdated()
            .block(TIMEOUT)
    }

    private companion object {
        /** One message interaction: [description], provider [state] with [parameters], keyed by [key]. */
        @Suppress("LongParameterList") // one value per column of the pact-interactions.md tables
        fun message(
            builder: PactBuilder,
            description: String,
            state: String,
            parameters: Map<String, Any>,
            key: String,
            body: DslPart,
        ): V4Pact =
            builder
                .expectsToReceiveMessageInteraction(description) { interaction ->
                    interaction.state(state, parameters).withContents { contents ->
                        contents.withMetadata(mapOf("topic" to Topic.ORDER, "kafkaKey" to key)).withContent(body)
                    }
                }.toPact()

        /** The envelope of an order event with the payload members catalog reads: `orderId` (and [extra]). */
        fun envelope(
            eventId: String,
            type: String,
            occurredAt: String,
            orderId: String,
            extra: (LambdaDslObject) -> Unit = {},
        ): DslPart =
            newJsonBody { envelope ->
                envelope.uuid("eventId", UUID.fromString(eventId))
                envelope.stringValue("type", type)
                envelope.numberValue("version", 1)
                envelope.stringMatcher("occurredAt", TIMESTAMP_REGEX, occurredAt)
                envelope.stringValue("aggregateId", orderId)
                envelope.stringMatcher("correlationId", CORRELATION_REGEX, CORRELATION_ID)
                envelope.stringValue("producer", PROVIDER)
                envelope.`object`("payload") { payload ->
                    payload.stringValue("orderId", orderId)
                    extra(payload)
                }
            }.build()
    }
}

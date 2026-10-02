package com.ecommerce.catalog.infrastructure

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.ReleaseReason
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.ReservationState
import com.ecommerce.catalog.domain.StockLine
import com.ecommerce.catalog.infrastructure.messaging.StockLinePayload
import com.ecommerce.catalog.infrastructure.messaging.StockReleasedPayload
import com.ecommerce.catalog.infrastructure.messaging.StockReservationPayload
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.testing.PostgresTestConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private val SEEDED_AT: Instant = Instant.parse("2026-10-02T08:00:00Z")
private val FIXTURE_CATEGORY: UUID = UUID.fromString("5eed0000-0000-4000-8000-0000000000ff")
private const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
private const val SKU_DIGITS = 8

/**
 * Provider side for every consumer of the catalog (pact-interactions.md sections 2.3, 2.7 and 5): replays the pacts
 * of the repository root `build/pacts` (`pact.folder`) against the running service. Before each interaction the
 * catalogue is emptied; the provider states then create exactly the rows their parameters describe. HTTP
 * interactions run against the server, message interactions (stock events, none consumed under a pact yet) against
 * the `@PactVerifyProvider` methods below. Tagged `provider` (runs in `contractVerify`); skipped, not failed, while
 * no consumer has written a pact for the catalog.
 */
@Tag("provider")
@Provider("catalog")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class)
@Suppress("TooManyFunctions") // one method per provider state and per message description
class CatalogProviderVerificationTest(
    @LocalServerPort private val port: Int,
    @Autowired private val database: DatabaseClient,
) {
    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.let {
            it.target =
                if (it.interaction.isAsynchronousMessage()) {
                    MessageTestTarget(listOf(CatalogProviderVerificationTest::class.java.packageName))
                } else {
                    HttpTestTarget("localhost", port)
                }
        }
        execute(
            "TRUNCATE reservation_lines, reservations, stock_adjustments, product_images, inventory_levels, " +
                "products, categories, outbox, processed_event",
        )
        execute(
            "INSERT INTO categories (id, name, created_at, updated_at, version) " +
                "VALUES (:id, 'Pact fixtures', :at, :at, 0)",
            mapOf("id" to FIXTURE_CATEGORY, "at" to SEEDED_AT),
        )
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun catalogHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("the catalogue service is running")
    fun serviceRunning() {
        // The Spring context and its database are already up; nothing to arrange.
    }

    @State("a product exists")
    fun productExists(parameters: Map<String, Any?>) = seedProduct(parameters, "active")

    @State("a product is withdrawn")
    fun productIsWithdrawn(parameters: Map<String, Any?>) = seedProduct(parameters, "withdrawn")

    @State("no product exists")
    fun noProductExists(parameters: Map<String, Any?>) {
        execute("DELETE FROM products WHERE id = :id", mapOf("id" to uuid(parameters, "productId")))
    }

    /** The reservation of the parameters, with the products of its lines and the stock its state implies. */
    @State("a reservation exists")
    fun reservationExists(parameters: Map<String, Any?>) {
        val state = text(parameters, "state")
        val reservationId = uuid(parameters, "reservationId")
        val expiresAt = Instant.parse(text(parameters, "expiresAt"))
        val lines = (parameters["lines"] as List<*>).map { it as Map<*, *> }
        lines.forEach { line ->
            val quantity = (line["quantity"] as Number).toInt()
            seedFixtureProduct(
                UUID.fromString(line["productId"].toString()),
                quantity,
                if (state ==
                    "reserved"
                ) {
                    quantity
                } else {
                    0
                },
            )
        }
        execute(
            "INSERT INTO reservations (id, order_id, state, restocked, created_at, expires_at, resolved_at, version) " +
                "VALUES (:id, :orderId, :state, false, :createdAt, :expiresAt, NULL, 0)",
            mapOf(
                "id" to reservationId,
                "orderId" to uuid(parameters, "orderId"),
                "state" to state,
                "createdAt" to expiresAt.minus(Reservation.DEFAULT_TTL),
                "expiresAt" to expiresAt,
            ),
        )
        insertLines(reservationId, lines)
    }

    private fun insertLines(
        reservationId: UUID,
        lines: List<Map<*, *>>,
    ) {
        lines.forEachIndexed { position, line ->
            execute(
                "INSERT INTO reservation_lines (reservation_id, product_id, quantity, position) " +
                    "VALUES (:reservationId, :productId, :quantity, :position)",
                mapOf(
                    "reservationId" to reservationId,
                    "productId" to UUID.fromString(line["productId"].toString()),
                    "quantity" to (line["quantity"] as Number).toInt(),
                    "position" to position,
                ),
            )
        }
    }

    @PactVerifyProvider("a StockReserved event for a new reservation")
    fun stockReserved(): MessageAndMetadata =
        message(
            EventType.StockReserved,
            StockReservationPayload.of(EXAMPLE, withExpiry = true),
        )

    @PactVerifyProvider("a StockCommitted event for a paid order")
    fun stockCommitted(): MessageAndMetadata =
        message(
            EventType.StockCommitted,
            StockReservationPayload.of(EXAMPLE.copy(state = ReservationState.COMMITTED), withExpiry = false),
        )

    @PactVerifyProvider("a StockReservationReleased event for a released reservation")
    fun stockReleased(): MessageAndMetadata =
        message(
            EventType.StockReservationReleased,
            StockReleasedPayload(
                EXAMPLE.id.value,
                EXAMPLE.orderId.value,
                StockLinePayload.of(EXAMPLE),
                ReleaseReason.PAYMENT_FAILED.name,
                restocked = false,
            ),
        )

    private fun message(
        type: EventType,
        payload: Any,
    ): MessageAndMetadata {
        val envelope = EnvelopeFactory("catalog").create(type, EXAMPLE.id.value, CORRELATION_ID, payload)
        return MessageAndMetadata(
            EnvelopeJson.write(envelope).toByteArray(),
            mapOf("topic" to Topic.STOCK, "kafkaKey" to EXAMPLE.id.value.toString()),
        )
    }

    private fun seedProduct(
        parameters: Map<String, Any?>,
        saleState: String,
    ) {
        val productId = uuid(parameters, "productId")
        execute("DELETE FROM products WHERE id = :id", mapOf("id" to productId))
        insertProduct(
            productId,
            text(parameters, "sku"),
            text(parameters, "name"),
            (parameters["priceMinor"] as Number).toLong(),
            text(parameters, "currency"),
            saleState,
        )
        insertStock(productId, (parameters["available"] as Number).toInt(), 0)
    }

    /** A product a reservation line refers to, when no state created it: [onHand] units, [reserved] of them held. */
    private fun seedFixtureProduct(
        productId: UUID,
        onHand: Int,
        reserved: Int,
    ) {
        val sku = "PACT-" + productId.toString().take(SKU_DIGITS).uppercase(Locale.ROOT)
        insertProduct(productId, sku, "Fixture $sku", FIXTURE_PRICE, "BRL", "active")
        insertStock(productId, onHand, reserved)
    }

    @Suppress("LongParameterList") // the columns of a product row
    private fun insertProduct(
        productId: UUID,
        sku: String,
        name: String,
        priceMinor: Long,
        currency: String,
        saleState: String,
    ) {
        execute(
            "INSERT INTO products (id, sku, name, description, price_minor, currency, category_id, sale_state, " +
                "created_at, updated_at, version) VALUES (:id, :sku, :name, NULL, :price, :currency, :category, " +
                ":saleState, :at, :at, 0)",
            mapOf(
                "id" to productId,
                "sku" to sku,
                "name" to name,
                "price" to priceMinor,
                "currency" to currency,
                "category" to FIXTURE_CATEGORY,
                "saleState" to saleState,
                "at" to SEEDED_AT,
            ),
        )
    }

    private fun insertStock(
        productId: UUID,
        onHand: Int,
        reserved: Int,
    ) {
        execute(
            "INSERT INTO inventory_levels (product_id, on_hand, reserved, version, updated_at) " +
                "VALUES (:id, :onHand, :reserved, 0, :at)",
            mapOf("id" to productId, "onHand" to onHand, "reserved" to reserved, "at" to SEEDED_AT),
        )
    }

    private fun execute(
        sql: String,
        bindings: Map<String, Any> = emptyMap(),
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    private companion object {
        const val FIXTURE_PRICE = 1000L

        /** The reservation of order 1 (pact-interactions.md fixed identifiers). */
        val EXAMPLE: Reservation =
            Reservation(
                ReservationId(UUID.fromString("4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15")),
                OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")),
                listOf(
                    line("9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01", 1),
                    line("3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02", 2),
                ),
                ReservationState.RESERVED,
                Instant.parse("2026-10-02T10:15:00Z"),
                Instant.parse("2026-10-02T11:00:00Z"),
                null,
                false,
                0,
            )

        fun line(
            productId: String,
            quantity: Int,
        ): StockLine =
            StockLine(
                ProductId(UUID.fromString(productId)),
                checkNotNull(Quantity.of(quantity).getOrNull()),
            )

        fun uuid(
            parameters: Map<String, Any?>,
            name: String,
        ): UUID = UUID.fromString(text(parameters, name))

        fun text(
            parameters: Map<String, Any?>,
            name: String,
        ): String = checkNotNull(parameters[name]) { "provider state parameter $name" }.toString()
    }
}

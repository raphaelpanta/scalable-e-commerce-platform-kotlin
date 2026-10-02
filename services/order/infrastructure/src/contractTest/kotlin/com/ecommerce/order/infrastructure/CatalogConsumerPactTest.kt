package com.ecommerce.order.infrastructure

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.order.application.ReservationResult
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.StockLine
import com.ecommerce.order.domain.StockShortage
import com.ecommerce.order.infrastructure.PactValues.BEANS
import com.ecommerce.order.infrastructure.PactValues.ESPRESSO
import com.ecommerce.order.infrastructure.PactValues.KETTLE
import com.ecommerce.order.infrastructure.PactValues.ORDER_1
import com.ecommerce.order.infrastructure.PactValues.ORDER_4
import com.ecommerce.order.infrastructure.PactValues.RESERVATION
import com.ecommerce.order.infrastructure.PactValues.UNKNOWN_PRODUCT
import com.ecommerce.order.infrastructure.clients.StockReservationClient
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

private const val ESPRESSO_PRICE = 14_900L
private const val BEANS_PRICE = 2_450L
private const val KETTLE_PRICE = 25_900L
private const val ESPRESSO_AVAILABLE = 5
private const val BEANS_AVAILABLE = 10
private const val KETTLE_AVAILABLE = 3
private const val EXPIRES_AT = "2026-10-02T11:00:00Z"
private const val RESERVATIONS = "/internal/reservations"
private const val SHORTAGE_DETAIL = "One or more products cannot be reserved in the requested quantity."

private fun product(
    id: String,
    sku: String,
    name: String,
    priceMinor: Long,
    available: Int,
): Map<String, Any> =
    mapOf(
        "productId" to id,
        "sku" to sku,
        "name" to name,
        "priceMinor" to priceMinor,
        "currency" to "BRL",
        "available" to available,
    )

private val ESPRESSO_STATE = product(ESPRESSO, "ESP-MACH-01", "Espresso Machine", ESPRESSO_PRICE, ESPRESSO_AVAILABLE)
private val BEANS_STATE = product(BEANS, "CB-1KG", "Coffee Beans 1kg", BEANS_PRICE, BEANS_AVAILABLE)

private fun reservationState(state: String): Map<String, Any> =
    mapOf(
        "reservationId" to RESERVATION,
        "orderId" to ORDER_1,
        "state" to state,
        "lines" to
            listOf(
                mapOf("productId" to ESPRESSO, "quantity" to 1),
                mapOf("productId" to BEANS, "quantity" to 2),
            ),
        "expiresAt" to EXPIRES_AT,
    )

private fun stockLines(
    body: LambdaDslObject,
    lines: List<Pair<String, Int>>,
) {
    body.array("lines") { array ->
        lines.forEach { (productId, quantity) ->
            array.`object` {
                it.stringValue("productId", productId)
                it.numberValue("quantity", quantity)
            }
        }
    }
}

private val ORDER_1_LINES = listOf(ESPRESSO to 1, BEANS to 2)

private val RESERVE_ORDER_1: DslPart =
    json { body ->
        body.stringValue("orderId", ORDER_1)
        stockLines(body, ORDER_1_LINES)
    }

private fun PactDslWithState.reserve(description: String) =
    internalRequest(description, "POST", RESERVATIONS).jsonBody(RESERVE_ORDER_1)

private fun shortage(
    body: LambdaDslObject,
    vararg lines: Triple<String, Int, Int>,
) {
    body.stringValue("instance", RESERVATIONS)
    body.array("unavailableLines") { array ->
        lines.forEach { (productId, requested, available) ->
            array.`object` {
                it.stringValue("productId", productId)
                it.numberValue("requested", requested)
                it.numberValue("available", available)
            }
        }
    }
}

private fun pricingItem(
    item: LambdaDslObject,
    state: Map<String, Any>,
) {
    item.stringValue("productId", state.getValue("productId").toString())
    item.stringValue("sku", state.getValue("sku").toString())
    item.stringValue("name", state.getValue("name").toString())
    item.money("price", state.getValue("priceMinor") as Long)
    item.numberValue("available", state.getValue("available") as Int)
    item.stringValue("saleState", "active")
}

/** Consumer side of order -> catalog (pact-interactions.md section 2.3), through the real [StockReservationClient]. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "catalog", pactVersion = PactSpecVersion.V4)
@Suppress("TooManyFunctions") // one pact method and one test per interaction of the table
class CatalogConsumerPactTest {
    @Pact(consumer = "order")
    fun reserveStock(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a product exists", ESPRESSO_STATE)
            .given("a product exists", BEANS_STATE)
            .reserve("a request to reserve stock for an order")
            .jsonAnswer(
                PactValues.CREATED,
                json { body ->
                    body.uuid("reservationId", UUID.fromString(RESERVATION))
                    body.stringValue("orderId", ORDER_1)
                    body.stringValue("state", "reserved")
                    stockLines(body, ORDER_1_LINES)
                    body.stringMatcher("expiresAt", PactValues.TIMESTAMP_REGEX, EXPIRES_AT)
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun reserveAgain(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a reservation exists", reservationState("reserved"))
            .reserve("a request to reserve stock for an order that already has a reservation")
            .jsonAnswer(
                PactValues.OK,
                json { body ->
                    body.stringValue("reservationId", RESERVATION)
                    body.stringValue("orderId", ORDER_1)
                    body.stringValue("state", "reserved")
                    stockLines(body, ORDER_1_LINES)
                    body.stringValue("expiresAt", EXPIRES_AT)
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun reserveTooMuch(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a product exists", ESPRESSO_STATE)
            .given("a product exists", BEANS_STATE + ("available" to 1))
            .reserve("a request to reserve more stock than is available")
            .problemAnswer(PactValues.CONFLICT, "insufficient-stock", "Insufficient stock", SHORTAGE_DETAIL) {
                shortage(it, Triple(BEANS, 2, 1))
            }.toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun reserveUnsellable(builder: PactDslWithProvider): V4Pact =
        builder
            .given(
                "a product is withdrawn",
                product(KETTLE, "KTL-VINT-01", "Vintage Kettle", KETTLE_PRICE, KETTLE_AVAILABLE),
            ).given("no product exists", mapOf("productId" to UNKNOWN_PRODUCT))
            .internalRequest("a request to reserve stock for withdrawn and unknown products", "POST", RESERVATIONS)
            .jsonBody(
                json { body ->
                    body.stringValue("orderId", ORDER_4)
                    stockLines(body, listOf(KETTLE to 1, UNKNOWN_PRODUCT to 1))
                },
            ).problemAnswer(PactValues.CONFLICT, "insufficient-stock", "Insufficient stock", SHORTAGE_DETAIL) {
                shortage(it, Triple(KETTLE, 1, 0), Triple(UNKNOWN_PRODUCT, 1, 0))
            }.toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun commit(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a reservation exists", reservationState("reserved"))
            .internalRequest("a request to commit a reservation", "POST", "$RESERVATIONS/$RESERVATION/commit")
            .noContent()
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun commitAgain(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a reservation exists", reservationState("committed"))
            .internalRequest(
                "a request to commit a reservation that is already committed",
                "POST",
                "$RESERVATIONS/$RESERVATION/commit",
            ).noContent()
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun release(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a reservation exists", reservationState("reserved"))
            .internalRequest("a request to release a reservation", "POST", "$RESERVATIONS/$RESERVATION/release")
            .noContent()
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun releaseAgain(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a reservation exists", reservationState("released"))
            .internalRequest(
                "a request to release a reservation that is already released",
                "POST",
                "$RESERVATIONS/$RESERVATION/release",
            ).noContent()
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun pricing(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a product exists", ESPRESSO_STATE)
            .given("a product exists", BEANS_STATE)
            .given("no product exists", mapOf("productId" to UNKNOWN_PRODUCT))
            .internalRequest(
                "a request for the pricing of several products to freeze order lines",
                "POST",
                "/internal/products/pricing",
            ).jsonBody(
                json { body ->
                    body.array("productIds") {
                        it.stringValue(ESPRESSO)
                        it.stringValue(BEANS)
                        it.stringValue(UNKNOWN_PRODUCT)
                    }
                },
            ).jsonAnswer(
                PactValues.OK,
                json { body ->
                    body.array("items") { items ->
                        items.`object` { pricingItem(it, ESPRESSO_STATE) }
                        items.`object` { pricingItem(it, BEANS_STATE) }
                    }
                },
            ).toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "reserveStock")
    fun `stock is reserved for every line`(mockServer: MockServer) {
        reserveOrder1(mockServer) shouldBe ReservationResult.Reserved(ReservationId(UUID.fromString(RESERVATION)))
    }

    @Test
    @PactTestFor(pactMethod = "reserveAgain")
    fun `an existing reservation is returned`(mockServer: MockServer) {
        reserveOrder1(mockServer) shouldBe ReservationResult.Reserved(ReservationId(UUID.fromString(RESERVATION)))
    }

    @Test
    @PactTestFor(pactMethod = "reserveTooMuch")
    fun `a shortage refuses the reservation with the short lines`(mockServer: MockServer) {
        reserveOrder1(mockServer) shouldBe ReservationResult.Refused(listOf(StockShortage(product(BEANS), 2, 1)))
    }

    @Test
    @PactTestFor(pactMethod = "reserveUnsellable")
    fun `withdrawn and unknown products are short with nothing available`(mockServer: MockServer) {
        val lines = listOf(StockLine(product(KETTLE), Quantity(1)), StockLine(product(UNKNOWN_PRODUCT), Quantity(1)))
        withPactCorrelation { client(mockServer).reserve(OrderId(UUID.fromString(ORDER_4)), lines) } shouldBe
            ReservationResult.Refused(
                listOf(StockShortage(product(KETTLE), 1, 0), StockShortage(product(UNKNOWN_PRODUCT), 1, 0)),
            )
    }

    @Test
    @PactTestFor(pactMethod = "commit")
    fun `a reservation is committed`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).commit(RESERVATION_ID) }
    }

    @Test
    @PactTestFor(pactMethod = "commitAgain")
    fun `committing twice is harmless`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).commit(RESERVATION_ID) }
    }

    @Test
    @PactTestFor(pactMethod = "release")
    fun `a reservation is released`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).release(RESERVATION_ID) }
    }

    @Test
    @PactTestFor(pactMethod = "releaseAgain")
    fun `releasing twice is harmless`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).release(RESERVATION_ID) }
    }

    @Test
    @PactTestFor(pactMethod = "pricing")
    fun `prices are read for the known products, in request order`(mockServer: MockServer) {
        val prices =
            withPactCorrelation {
                client(
                    mockServer,
                ).pricing(listOf(product(ESPRESSO), product(BEANS), product(UNKNOWN_PRODUCT)))
            }

        prices.map { it.productId } shouldBe listOf(product(ESPRESSO), product(BEANS))
        prices.map { it.price } shouldBe listOf(Money(ESPRESSO_PRICE, "BRL"), Money(BEANS_PRICE, "BRL"))
        prices.map { it.name } shouldBe listOf("Espresso Machine", "Coffee Beans 1kg")
        prices.all { it.active } shouldBe true
    }

    private fun reserveOrder1(mockServer: MockServer): ReservationResult =
        withPactCorrelation {
            client(mockServer).reserve(
                OrderId(UUID.fromString(ORDER_1)),
                listOf(StockLine(product(ESPRESSO), Quantity(1)), StockLine(product(BEANS), Quantity(2))),
            )
        }

    private fun client(mockServer: MockServer) = StockReservationClient(internalClient(mockServer))

    private fun product(id: String) = ProductId(UUID.fromString(id))

    private companion object {
        val RESERVATION_ID = ReservationId(UUID.fromString(RESERVATION))
    }
}

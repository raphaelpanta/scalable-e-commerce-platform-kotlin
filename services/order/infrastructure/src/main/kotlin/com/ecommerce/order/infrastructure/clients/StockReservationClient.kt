package com.ecommerce.order.infrastructure.clients

import com.ecommerce.order.application.CatalogPort
import com.ecommerce.order.application.ReservationResult
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.ProductPrice
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.StockLine
import com.ecommerce.order.domain.StockShortage
import com.ecommerce.platform.http.ProblemClientException
import com.ecommerce.platform.http.awaitBodyOrProblem
import com.ecommerce.platform.http.awaitOptionalBodyOrProblem
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/** `ReserveStockRequest` of catalog-internal.yaml. */
data class ReserveStockJson(
    val orderId: UUID,
    val lines: List<StockLineJson>,
)

/** `StockLine`. */
data class StockLineJson(
    val productId: UUID,
    val quantity: Int,
)

/** `Reservation` (the fields the order reads). */
data class ReservationJson(
    val reservationId: UUID,
)

/** `ProductsPricingRequest`. */
data class PricingRequestJson(
    val productIds: List<UUID>,
)

/** `ProductsPricing`. */
data class PricingJson(
    val items: List<Item>,
) {
    /** `ProductPricing`. */
    data class Item(
        val productId: UUID,
        val sku: String,
        val name: String,
        val price: MoneyJson,
        val available: Int,
        val saleState: String,
    ) {
        fun toPrice(): ProductPrice =
            ProductPrice(
                ProductId(productId),
                sku,
                name,
                Money(price.amountMinor, price.currency),
                available,
                saleState == ACTIVE,
            )
    }

    private companion object {
        const val ACTIVE = "active"
    }
}

/** [CatalogPort] over catalog-internal.yaml: pricing and the synchronous stock reservation (ADR 0002). */
class StockReservationClient(
    private val client: WebClient,
) : CatalogPort {
    override suspend fun pricing(productIds: List<ProductId>): List<ProductPrice> =
        required(SERVICE) {
            client
                .post()
                .uri("/internal/products/pricing")
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(PricingRequestJson(productIds.map { it.value }))
                .awaitBodyOrProblem<PricingJson>()
        }.fold({ throw unavailable(SERVICE, it) }, { body -> body.items.map { it.toPrice() } })

    override suspend fun reserve(
        orderId: OrderId,
        lines: List<StockLine>,
    ): ReservationResult =
        required(SERVICE) {
            client
                .post()
                .uri("/internal/reservations")
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(
                    ReserveStockJson(orderId.value, lines.map { StockLineJson(it.productId.value, it.quantity.value) }),
                ).awaitBodyOrProblem<ReservationJson>()
        }.fold(
            { failure -> ReservationResult.Refused(shortagesOf(failure) ?: throw unavailable(SERVICE, failure)) },
            { ReservationResult.Reserved(ReservationId(it.reservationId)) },
        )

    override suspend fun commit(reservationId: ReservationId) {
        tolerated("Committing the reservation") { transition(reservationId, "commit") }
    }

    override suspend fun release(reservationId: ReservationId) {
        tolerated("Releasing the reservation") { transition(reservationId, "release") }
    }

    private suspend fun transition(
        reservationId: ReservationId,
        action: String,
    ): ProblemClientException? =
        client
            .post()
            .uri("/internal/reservations/{reservationId}/{action}", reservationId.value, action)
            .accept(MediaType.APPLICATION_JSON)
            .awaitOptionalBodyOrProblem<String>()
            .leftOrNull()

    private companion object {
        const val SERVICE = "catalog"
        const val UNAVAILABLE_LINES = "unavailableLines"

        /** The `unavailableLines` of a 409 `insufficient-stock` answer, or null for any other failure. */
        fun shortagesOf(failure: ProblemClientException): List<StockShortage>? =
            (failure.problem?.extensions?.get(UNAVAILABLE_LINES) as? List<*>)
                ?.takeIf { failure.isType("insufficient-stock") }
                ?.filterIsInstance<Map<*, *>>()
                ?.map { line ->
                    StockShortage(
                        ProductId(UUID.fromString(line["productId"].toString())),
                        (line["requested"] as Number).toInt(),
                        (line["available"] as Number).toInt(),
                    )
                }
    }
}

package com.ecommerce.cart.infrastructure.catalog

import com.ecommerce.cart.application.CatalogPricing
import com.ecommerce.cart.domain.Money
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.ProductQuote
import com.ecommerce.cart.domain.SaleState
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.http.WebClientDefaults
import com.ecommerce.platform.http.awaitBodyOrProblem
import com.ecommerce.platform.problem.ProblemException
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import java.util.UUID

/**
 * Outbound adapter: the catalog's internal pricing endpoints (catalog-internal.yaml `getProductPricing`,
 * `getProductsPricing`) over a non-blocking internal WebClient ([WebClientDefaults.internalClient]: platform DNS
 * round-robin to `http://catalog:8080`, `X-Internal-Token`, the caller's `X-Correlation-Id`, connection retries).
 * Only a 404 of the single lookup means "unknown product"; any other failure, or no answer at all, is a 503
 * `unavailable` for the shopper, because a cart cannot be priced without the catalogue.
 */
class CatalogPricingAdapter(
    private val client: WebClient,
) : CatalogPricing {
    override suspend fun quote(productId: ProductId): ProductQuote? =
        reachingCatalog {
            client
                .get()
                .uri("/internal/products/{productId}/pricing", productId.value)
                .accept(MediaType.APPLICATION_JSON)
                .awaitBodyOrProblem<ProductPricingJson>()
        }.fold({ failure -> if (failure.status == NOT_FOUND) null else throw unavailable(failure) }) { it.toQuote() }

    override suspend fun quotes(productIds: Collection<ProductId>): Map<ProductId, ProductQuote> =
        productIds
            .distinct()
            .chunked(MAX_IDS_PER_CALL)
            .flatMap { chunk -> pricingOf(chunk) }
            .associateBy { it.productId }

    private suspend fun pricingOf(productIds: List<ProductId>): List<ProductQuote> =
        reachingCatalog {
            client
                .post()
                .uri("/internal/products/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(ProductsPricingRequestJson(productIds.map { it.value }))
                .awaitBodyOrProblem<ProductsPricingJson>()
        }.fold({ failure -> throw unavailable(failure) }) { pricing -> pricing.items.map { it.toQuote() } }

    private suspend fun <T> reachingCatalog(call: suspend () -> T): T =
        try {
            call()
        } catch (failure: WebClientRequestException) {
            throw unavailable(failure)
        }

    companion object {
        /** The batch endpoint accepts at most 100 ids per call. */
        const val MAX_IDS_PER_CALL: Int = 100
        private const val NOT_FOUND = 404

        /** The adapter on an internal client for [baseUrl] that sends [internalToken]. */
        fun create(
            builder: WebClient.Builder,
            baseUrl: String,
            internalToken: String,
        ): CatalogPricingAdapter =
            CatalogPricingAdapter(WebClientDefaults.internalClient(builder, baseUrl, internalToken))

        private fun unavailable(cause: Throwable): ProblemException =
            ProblemException(Problem.unavailable("The catalogue is unavailable; try again later."), cause = cause)
    }
}

/** `{"productIds": [...]}` (catalog-internal.yaml `ProductsPricingRequest`). */
data class ProductsPricingRequestJson(
    val productIds: List<UUID>,
)

/** `{"items": [...]}` (catalog-internal.yaml `ProductsPricing`). */
data class ProductsPricingJson(
    val items: List<ProductPricingJson>,
)

/** One product's pricing (catalog-internal.yaml `ProductPricing`). */
data class ProductPricingJson(
    val productId: UUID,
    val sku: String,
    val name: String,
    val price: PriceJson,
    val available: Int,
    val saleState: String,
) {
    fun toQuote(): ProductQuote =
        ProductQuote(
            productId = ProductId(productId),
            sku = sku,
            name = name,
            price = Money(price.amountMinor, price.currency),
            available = available,
            saleState = if (saleState == ACTIVE) SaleState.ACTIVE else SaleState.WITHDRAWN,
        )

    private companion object {
        const val ACTIVE = "active"
    }
}

/** `{"amountMinor": 1999, "currency": "BRL"}`. */
data class PriceJson(
    val amountMinor: Long,
    val currency: String,
)

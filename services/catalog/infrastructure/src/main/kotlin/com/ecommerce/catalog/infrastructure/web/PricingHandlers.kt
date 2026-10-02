package com.ecommerce.catalog.infrastructure.web

import arrow.core.raise.either
import com.ecommerce.catalog.application.GetPricing
import com.ecommerce.catalog.application.GetPricingBatch
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.platform.problem.toServerResponse
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * Inbound adapter: the pricing endpoints of catalog-internal.yaml (cart and order), behind `X-Internal-Token`.
 * Withdrawn products are answered with `saleState: withdrawn`; only an unknown single product is 404, unknown ids
 * are omitted from a batch.
 */
class PricingHandlers(
    private val getPricing: GetPricing,
    private val getPricingBatch: GetPricingBatch,
) {
    suspend fun pricing(request: ServerRequest): ServerResponse =
        either {
            val productId = ProductId(request.uuidPath("productId").bind())
            getPricing(productId).mapLeft { it.toProblem(BAD_REQUEST) }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun batch(request: ServerRequest): ServerResponse =
        either {
            val body = request.jsonBody<ProductsPricingRequest>().bind()
            val ids = required(body.productIds, "productIds").bind()
            val productIds = ids.mapIndexed { index, id -> ProductId(required(id, "productIds[$index]").bind()) }
            getPricingBatch(productIds).mapLeft { it.toProblem(BAD_REQUEST) }.bind()
        }.toServerResponse(request) { priced -> ok(ProductsPricingJson(priced.map { it.toJson() })) }
}

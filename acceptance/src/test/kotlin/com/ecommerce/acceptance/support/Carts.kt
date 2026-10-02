package com.ecommerce.acceptance.support

import tools.jackson.databind.JsonNode

/**
 * Who operates a cart (cart.yaml): a signed-in shopper (bearer, account cart) or an anonymous visitor identified by
 * the `X-Cart-Token` the cart service issued on the first write.
 */
class CartHolder(
    var bearer: String? = null,
    var token: String? = null,
) {
    fun headers(): Map<String, String> =
        token?.takeIf { bearer == null }?.let { mapOf(Headers.CART_TOKEN to it) }.orEmpty()
}

/** Cart operations; every cart answer is kept as the holder's latest view. */
class Carts(
    private val api: ApiClient,
) {
    var latest: JsonNode? = null
        private set

    fun view(holder: CartHolder): ApiResponse = remember(holder, api.get(Paths.CART, holder.bearer, holder.headers()))

    fun add(
        holder: CartHolder,
        productId: String,
        quantity: Int,
    ): ApiResponse {
        val body = mapOf("productId" to productId, "quantity" to quantity)
        return remember(holder, api.post(Paths.CART_LINES, body, holder.bearer, holder.headers()))
    }

    fun setQuantity(
        holder: CartHolder,
        lineId: String,
        quantity: Int,
    ): ApiResponse {
        val body = mapOf("quantity" to quantity)
        return remember(holder, api.put(Paths.cartLine(lineId), body, holder.bearer, holder.headers()))
    }

    fun remove(
        holder: CartHolder,
        lineId: String,
    ): ApiResponse = remember(holder, api.delete(Paths.cartLine(lineId), holder.bearer, holder.headers()))

    /** Merges the anonymous cart [token] into the account cart of [bearer] (`mergeCart`). */
    fun merge(
        bearer: String,
        token: String,
    ): ApiResponse = api.post(Paths.CART_MERGE, null, bearer, mapOf(Headers.CART_TOKEN to token))

    private fun remember(
        holder: CartHolder,
        response: ApiResponse,
    ): ApiResponse {
        if (response.status in Status.OK..Status.CREATED) {
            latest = response.body
            if (holder.bearer == null) response.header(Headers.CART_TOKEN)?.let { holder.token = it }
        }
        return response
    }

    companion object {
        /** The line of [cart] for [productId], if any. */
        fun lineFor(
            cart: JsonNode,
            productId: String,
        ): JsonNode? = cart.list("lines").firstOrNull { it.string("productId") == productId }
    }
}

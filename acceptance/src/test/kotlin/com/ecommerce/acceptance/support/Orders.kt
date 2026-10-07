package com.ecommerce.acceptance.support

import java.util.UUID

/** One checkout request (`placeOrder`): kept so that it can be resubmitted verbatim with its idempotency key. */
class Checkout(
    val bearer: String?,
    val body: Map<String, Any>,
    val idempotencyKey: String = UUID.randomUUID().toString(),
) {
    val answers: MutableList<ApiResponse> = mutableListOf()
    var correlationId: String = UUID.randomUUID().toString()
        private set

    val lastAnswer: ApiResponse
        get() = answers.last()

    fun headers(): Map<String, String> {
        correlationId = UUID.randomUUID().toString()
        return mapOf(Headers.IDEMPOTENCY_KEY to idempotencyKey, Headers.CORRELATION_ID to correlationId)
    }

    companion object {
        fun body(
            addressId: String,
            cartRevision: String,
            cardToken: String,
        ): Map<String, Any> =
            mapOf(
                "addressId" to addressId,
                "cartRevision" to cartRevision,
                "paymentMethod" to mapOf("type" to "card", "token" to cardToken),
            )
    }
}

/** Order operations (order.yaml) and the payment views of an order (payment.yaml). */
class Orders(
    private val api: ApiClient,
) {
    /** Sends (or resends) [checkout]; every answer is appended to it. */
    fun place(checkout: Checkout): ApiResponse =
        api.post(Paths.ORDERS, checkout.body, checkout.bearer, checkout.headers()).also { checkout.answers += it }

    fun get(
        bearer: String,
        orderId: String,
    ): ApiResponse = api.get(Paths.order(orderId), bearer)

    fun list(bearer: String): ApiResponse = api.get(Paths.query(Paths.ORDERS, "size" to Catalogue.MAX_PAGE), bearer)

    /** The orders in [orderStatus] (`listOwnOrders` with its `orderStatus` filter): an operator receives every shopper's. */
    fun listWithStatus(
        bearer: String,
        orderStatus: String,
    ): ApiResponse =
        api.get(
            Paths.query(Paths.ORDERS, "orderStatus" to orderStatus, "size" to Catalogue.MAX_PAGE),
            bearer,
        )

    fun cancel(
        bearer: String,
        orderId: String,
    ): ApiResponse = api.post(Paths.cancellation(orderId), null, bearer)

    fun transition(
        operatorBearer: String,
        orderId: String,
        orderStatus: String,
    ): ApiResponse = api.post(Paths.orderStatus(orderId), mapOf("orderStatus" to orderStatus), operatorBearer)

    fun paymentAttempts(
        bearer: String,
        orderId: String,
    ): ApiResponse = api.get(Paths.query(Paths.PAYMENT_ATTEMPTS, "orderId" to orderId), bearer)

    fun refunds(
        bearer: String,
        orderId: String,
    ): ApiResponse = api.get(Paths.query(Paths.REFUNDS, "orderId" to orderId), bearer)
}

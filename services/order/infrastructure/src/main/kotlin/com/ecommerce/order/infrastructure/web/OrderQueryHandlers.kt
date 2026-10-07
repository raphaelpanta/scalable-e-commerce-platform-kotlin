package com.ecommerce.order.infrastructure.web

import arrow.core.raise.either
import com.ecommerce.order.application.GetOwnOrder
import com.ecommerce.order.application.ListOwnOrders
import com.ecommerce.platform.problem.toServerResponse
import com.ecommerce.platform.security.requireAccount
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait

/** `listOwnOrders` and `getOwnOrder` of order.yaml. */
class OrderQueryHandlers(
    private val listOwnOrders: ListOwnOrders,
    private val getOwnOrder: GetOwnOrder,
) {
    /**
     * `GET /api/v1/orders?page&size&orderStatus`: the shopper's orders, or every order for an operator, newest first
     * (the application layer decides by role); `orderStatus` narrows the page for both.
     */
    suspend fun listOwnOrders(request: ServerRequest): ServerResponse =
        either {
            val account = requireAccount().bind()
            val page = OrderRequests.page(request).bind()
            val status = OrderRequests.orderStatus(request).bind()
            listOwnOrders(account.toCaller(), page, status).mapLeft(OrderProblems::of).bind()
        }.toServerResponse(request) { ok(OrderPageView.of(it)) }

    /** `GET /api/v1/orders/{orderId}`: own orders for shoppers, any order for operators, 404 otherwise. */
    suspend fun getOwnOrder(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind()
            val orderId = OrderRequests.orderId(request).bind()
            getOwnOrder(caller.toCaller(), orderId).mapLeft(OrderProblems::of).bind()
        }.toServerResponse(request) { ok(OrderView.of(it)) }

    private suspend fun ok(body: Any): ServerResponse =
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)
}

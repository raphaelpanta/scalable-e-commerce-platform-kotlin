package com.ecommerce.order.infrastructure.web

import arrow.core.raise.either
import com.ecommerce.order.application.CancelOwnOrder
import com.ecommerce.order.application.CheckoutOutcome
import com.ecommerce.order.application.CheckoutResult
import com.ecommerce.order.application.PlaceOrder
import com.ecommerce.order.application.PlaceOrderCommand
import com.ecommerce.order.application.TransitionOrderStatus
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.StoredResponse
import com.ecommerce.platform.problem.toServerResponse
import com.ecommerce.platform.security.requireOperator
import com.ecommerce.platform.security.requireShopper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import java.net.URI

/**
 * `placeOrder`, `cancelOwnOrder` and `transitionOrderStatus` of order.yaml. The role is checked here (401/403) and
 * again by the use case; expected failures come back as values and are answered as RFC 9457 problems.
 */
class OrderCommandHandlers(
    private val placeOrder: PlaceOrder,
    private val cancelOwnOrder: CancelOwnOrder,
    private val transitionOrderStatus: TransitionOrderStatus,
    private val responses: CheckoutResponseRenderer,
) {
    /** `POST /api/v1/orders`: 201 paid, 202 pending, 409 refused or cancelled meanwhile, 422 declined or key reused. */
    suspend fun placeOrder(request: ServerRequest): ServerResponse =
        either {
            val shopper = requireShopper().bind()
            val key = OrderRequests.idempotencyKey(request).bind()
            val checkout = OrderRequests.checkout(request.awaitBodyOrNull<Map<String, Any?>>()).bind()
            placeOrder(PlaceOrderCommand(shopper.toCaller(), key, checkout)).mapLeft(OrderProblems::of).bind()
        }.toServerResponse(request) { answer(request, it) }

    /** `POST /api/v1/orders/{orderId}/cancellation`. */
    suspend fun cancelOwnOrder(request: ServerRequest): ServerResponse =
        either {
            val shopper = requireShopper().bind()
            val orderId = OrderRequests.orderId(request).bind()
            cancelOwnOrder(shopper.toCaller(), orderId).mapLeft(OrderProblems::of).bind()
        }.toServerResponse(request) { ok(OrderView.of(it)) }

    /** `POST /api/v1/orders/{orderId}/status` (operators only). */
    suspend fun transitionOrderStatus(request: ServerRequest): ServerResponse =
        either {
            val operator = requireOperator().bind()
            val orderId = OrderRequests.orderId(request).bind()
            val target = OrderRequests.transition(request.awaitBodyOrNull<Map<String, Any?>>()).bind()
            transitionOrderStatus(operator.toCaller(), orderId, target).mapLeft(OrderProblems::of).bind()
        }.toServerResponse(request) { ok(OrderView.of(it)) }

    private suspend fun answer(
        request: ServerRequest,
        result: CheckoutResult,
    ): ServerResponse =
        when (result) {
            is CheckoutResult.Completed -> {
                when (val outcome = result.outcome) {
                    is CheckoutOutcome.Paid -> {
                        created(
                            outcome.order.id,
                            HttpStatus.CREATED,
                            OrderView.of(outcome.order),
                        )
                    }

                    is CheckoutOutcome.AwaitingPayment -> {
                        created(
                            outcome.order.id,
                            HttpStatus.ACCEPTED,
                            OrderView.of(outcome.order),
                        )
                    }

                    is CheckoutOutcome.Declined, is CheckoutOutcome.Cancelled -> {
                        refused(request, responses.snapshot(outcome))
                    }
                }
            }

            is CheckoutResult.Replayed -> {
                val stored = result.response
                if (stored.status >= HttpStatus.BAD_REQUEST.value()) {
                    refused(request, stored)
                } else {
                    created(result.orderId, HttpStatus.valueOf(stored.status), stored.body)
                }
            }
        }

    /** A checkout that created an order but answers a problem: 422 `payment-declined` or 409 `order-cancelled`. */
    private suspend fun refused(
        request: ServerRequest,
        stored: StoredResponse,
    ): ServerResponse {
        val members = responses.members(stored)
        val problem =
            if (stored.status == CheckoutResponseRenderer.CONFLICT) {
                OrderProblems.orderCancelled(members)
            } else {
                OrderProblems.paymentDeclined(members)
            }
        return problem.toServerResponse(request)
    }

    private suspend fun created(
        orderId: OrderId,
        status: HttpStatus,
        body: Any,
    ): ServerResponse {
        val builder =
            ServerResponse
                .status(status)
                .location(URI.create("$ORDERS/$orderId"))
                .contentType(MediaType.APPLICATION_JSON)
        if (status == HttpStatus.ACCEPTED) builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        return builder.bodyValueAndAwait(body)
    }

    private suspend fun ok(body: Any): ServerResponse =
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)

    private companion object {
        const val ORDERS = "/api/v1/orders"
        const val RETRY_AFTER_SECONDS = "30"
    }
}

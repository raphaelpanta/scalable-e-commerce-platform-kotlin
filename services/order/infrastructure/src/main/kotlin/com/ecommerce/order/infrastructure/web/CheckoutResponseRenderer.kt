package com.ecommerce.order.infrastructure.web

import com.ecommerce.order.application.CheckoutOutcome
import com.ecommerce.order.application.CheckoutResponses
import com.ecommerce.order.domain.StoredResponse
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * The answer of a checkout as it is stored for replays: 201 or 202 with the order (order.yaml `Order`), 422 with the
 * `payment-declined` extension members, or 409 with the `order-cancelled` ones (the problem itself is rebuilt per
 * request, with its correlation id).
 */
class CheckoutResponseRenderer(
    private val json: JsonMapper,
) : CheckoutResponses {
    override fun snapshot(outcome: CheckoutOutcome): StoredResponse =
        when (outcome) {
            is CheckoutOutcome.Paid -> {
                StoredResponse(HttpStatus.CREATED.value(), json.writeValueAsString(OrderView.of(outcome.order)))
            }

            is CheckoutOutcome.AwaitingPayment -> {
                StoredResponse(HttpStatus.ACCEPTED.value(), json.writeValueAsString(OrderView.of(outcome.order)))
            }

            is CheckoutOutcome.Declined -> {
                StoredResponse(UNPROCESSABLE, json.writeValueAsString(declinedMembers(outcome)))
            }

            is CheckoutOutcome.Cancelled -> {
                StoredResponse(CONFLICT, json.writeValueAsString(cancelledMembers(outcome)))
            }
        }

    /** The extension members of a stored problem answer (422 or 409). */
    fun members(stored: StoredResponse): Map<String, Any?> = json.readValue(stored.body)

    private fun declinedMembers(outcome: CheckoutOutcome.Declined): Map<String, Any?> =
        mapOf(
            "declineReason" to outcome.order.declineCategory?.wire,
            "orderId" to outcome.order.id.toString(),
        )

    private fun cancelledMembers(outcome: CheckoutOutcome.Cancelled): Map<String, Any?> =
        mapOf(
            "orderId" to outcome.order.id.toString(),
            "cancellationReason" to
                outcome.order.cancellation
                    ?.reason
                    ?.name,
        )

    companion object {
        /** Status of a declined checkout. */
        const val UNPROCESSABLE: Int = 422

        /** Status of a checkout whose order was cancelled while it was being charged. */
        const val CONFLICT: Int = 409
    }
}

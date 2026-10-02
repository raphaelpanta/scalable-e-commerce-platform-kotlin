package com.ecommerce.cart.infrastructure.messaging

import com.ecommerce.cart.application.CartEvents
import com.ecommerce.cart.application.CartMerged
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import java.util.UUID

/**
 * Outbound adapter: publishes the cart's events through the transactional outbox of `libs/platform-messaging`, in
 * the caller's transaction. `CartMerged` goes to `cart.cart.v1`, keyed by the account cart id, and carries the
 * correlation id of the request (a fresh one outside a request).
 */
class OutboxCartEvents(
    private val outbox: OutboxPublisher,
    private val envelopes: EnvelopeFactory,
) : CartEvents {
    override suspend fun cartMerged(event: CartMerged) {
        val correlationId = CorrelationIds.current() ?: UUID.randomUUID().toString()
        outbox.publish(
            envelopes.create(EventType.CartMerged, event.cartId.value, correlationId, CartMergedPayload.of(event)),
        )
    }
}

/** events.yaml `CartMergedPayload`. */
data class CartMergedPayload(
    val accountId: UUID,
    val cartId: UUID,
    val mergedCartCount: Int,
    val lineCount: Int,
    val cappedLines: List<CappedLinePayload>,
) {
    companion object {
        fun of(event: CartMerged): CartMergedPayload =
            CartMergedPayload(
                accountId = event.accountId.value,
                cartId = event.cartId.value,
                mergedCartCount = event.mergedCartCount,
                lineCount = event.lineCount,
                cappedLines =
                    event.cappedLines.map {
                        CappedLinePayload(
                            it.productId.value,
                            it.requestedQuantity,
                            it.appliedQuantity,
                        )
                    },
            )
    }
}

/** One capped line of `CartMergedPayload.cappedLines`. */
data class CappedLinePayload(
    val productId: UUID,
    val requestedQuantity: Int,
    val appliedQuantity: Int,
)

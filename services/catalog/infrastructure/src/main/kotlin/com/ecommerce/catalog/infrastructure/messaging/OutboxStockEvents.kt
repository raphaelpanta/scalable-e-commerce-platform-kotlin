package com.ecommerce.catalog.infrastructure.messaging

import com.ecommerce.catalog.application.StockEvents
import com.ecommerce.catalog.domain.ReleaseReason
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * Outbound adapter: publishes the stock events (events.yaml `catalog.stock.v1`) through the transactional outbox of
 * `libs/platform-messaging`, in the caller's transaction, keyed by the reservation id. The correlation id is the
 * originating one: the consumed event's, else the request's, else a fresh one (the expiry job).
 */
class OutboxStockEvents(
    private val outbox: OutboxPublisher,
    private val envelopes: EnvelopeFactory,
) : StockEvents {
    override suspend fun reserved(
        reservation: Reservation,
        correlationId: String?,
    ) = publish(EventType.StockReserved, reservation, correlationId, StockReservationPayload.of(reservation, true))

    override suspend fun committed(
        reservation: Reservation,
        correlationId: String?,
    ) = publish(EventType.StockCommitted, reservation, correlationId, StockReservationPayload.of(reservation, false))

    override suspend fun released(
        reservation: Reservation,
        reason: ReleaseReason,
        correlationId: String?,
    ) = publish(
        EventType.StockReservationReleased,
        reservation,
        correlationId,
        StockReleasedPayload(
            reservation.id.value,
            reservation.orderId.value,
            StockLinePayload.of(reservation),
            reason.name,
            reservation.restocked,
        ),
    )

    private suspend fun publish(
        type: EventType,
        reservation: Reservation,
        correlationId: String?,
        payload: Any,
    ) {
        val origin = correlationId ?: CorrelationIds.current() ?: UUID.randomUUID().toString()
        outbox.publish(envelopes.create(type, reservation.id.value, origin, payload))
    }
}

/** events.yaml `StockLine`. */
data class StockLinePayload(
    val productId: UUID,
    val quantity: Int,
) {
    companion object {
        fun of(reservation: Reservation): List<StockLinePayload> =
            reservation.lines.map { StockLinePayload(it.productId.value, it.quantity.value) }
    }
}

/** events.yaml `StockReservationPayload` (`StockReserved` and `StockCommitted`; `expiresAt` on the first only). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StockReservationPayload(
    val reservationId: UUID,
    val orderId: UUID,
    val lines: List<StockLinePayload>,
    val expiresAt: Instant?,
) {
    companion object {
        fun of(
            reservation: Reservation,
            withExpiry: Boolean,
        ): StockReservationPayload =
            StockReservationPayload(
                reservation.id.value,
                reservation.orderId.value,
                StockLinePayload.of(reservation),
                reservation.expiresAt.takeIf { withExpiry },
            )
    }
}

/**
 * events.yaml `StockReleasedPayload`: the stock-level [reason] and, additively, whether committed stock was put back
 * on hand ([restocked], data-model section 3.2).
 */
data class StockReleasedPayload(
    val reservationId: UUID,
    val orderId: UUID,
    val lines: List<StockLinePayload>,
    val reason: String,
    val restocked: Boolean,
)

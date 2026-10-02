package com.ecommerce.catalog.application

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.ReleaseReason
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.StockEffect
import com.ecommerce.catalog.domain.StockLine
import com.ecommerce.catalog.domain.Transition

/** How many times a transition that lost a concurrent race is tried in all. */
private const val ATTEMPTS = 3

/** The reservation of an order and whether this call created it (201) or found it (200, replay). */
data class Reserved(
    val reservation: Reservation,
    val created: Boolean,
)

/**
 * `reserveStock` (ADR 0002): all or nothing across the lines, idempotent per order. The shortage check runs on a
 * snapshot first (unknown and withdrawn products count as 0 available); the reservation is then stored and every
 * line reserved with a guarded update in one transaction, so a concurrent reservation of the same units fails here
 * and the whole transaction rolls back. Publishes `StockReserved` on creation only.
 */
class ReserveStock(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        orderId: OrderId,
        requested: List<Pair<ProductId, Int>>,
        correlationId: String? = null,
    ): Either<CatalogError, Reserved> {
        catalog.reservations.findByOrder(orderId)?.let { return Reserved(it, created = false).right() }
        return either {
            val lines = Reservation.lines(requested).bind()
            shortage(lines, catalog.sellable(lines.map(StockLine::productId)))?.let { raise(it) }
            val reservation =
                Reservation.open(
                    ReservationId(catalog.newId()),
                    orderId,
                    lines,
                    catalog.now(),
                    catalog.settings.reservationTtl,
                )
            catalog.transactions
                .inTransaction { place(reservation, correlationId) }
                .fold({ failure -> recover(orderId, failure) }, { it.right() })
                .bind()
        }
    }

    private suspend fun place(
        reservation: Reservation,
        correlationId: String?,
    ): Either<CatalogError, Reserved> =
        either {
            ensure(catalog.reservations.insert(reservation)) { CatalogError.ConcurrentUpdate }
            val held = mutableMapOf<ProductId, Int>()
            for (line in reservation.lines) {
                if (!catalog.inventory.tryReserve(line.productId, line.quantity)) {
                    val now = catalog.sellable(reservation.lines.map(StockLine::productId))
                    raise(shortage(reservation.lines) { now(it) + (held[it] ?: 0) } ?: CatalogError.ConcurrentUpdate)
                }
                held[line.productId] = line.quantity.value
            }
            catalog.events.reserved(reservation, correlationId)
            Reserved(reservation, created = true)
        }

    /** A concurrent call created the order's reservation first: answer with it (replay); other failures stand. */
    private suspend fun recover(
        orderId: OrderId,
        failure: CatalogError,
    ): Either<CatalogError, Reserved> =
        catalog.reservations
            .findByOrder(orderId)
            ?.takeIf { failure == CatalogError.ConcurrentUpdate }
            ?.let { Reserved(it, created = false).right() }
            ?: failure.left()

    private fun shortage(
        lines: List<StockLine>,
        availableOf: (ProductId) -> Int,
    ): CatalogError.InsufficientStock? =
        Reservation.shortages(lines, availableOf).toNonEmptyListOrNull()?.let(CatalogError::InsufficientStock)
}

/**
 * Applies the transition [step] to the reservation [load] finds, storing the changed reservation, moving the stock
 * of every line and publishing the event in one transaction. A transition that lost a race with another one (the
 * same reservation changed meanwhile) is re-read and tried again, so concurrent HTTP and event paths converge.
 */
internal suspend fun Catalog.resolve(
    load: suspend () -> Either<CatalogError, Reservation>,
    step: (Reservation) -> Either<CatalogError, Transition>,
    reason: ReleaseReason,
    correlationId: String?,
): Either<CatalogError, Transition> {
    var outcome: Either<CatalogError, Transition> = CatalogError.ConcurrentUpdate.left()
    var attempts = 0
    while (attempts < ATTEMPTS && outcome == CatalogError.ConcurrentUpdate.left()) {
        attempts++
        outcome =
            either {
                val reservation = load().bind()
                val transition = step(reservation).bind()
                if (transition is Transition.Changed) {
                    transactions.inTransaction { store(reservation.version, transition, reason, correlationId) }.bind()
                }
                transition
            }
    }
    return outcome
}

private suspend fun Catalog.store(
    expectedVersion: Long,
    changed: Transition.Changed,
    reason: ReleaseReason,
    correlationId: String?,
): Either<CatalogError, Unit> =
    either {
        val reservation = changed.reservation
        ensure(reservations.update(reservation, expectedVersion)) { CatalogError.ConcurrentUpdate }
        reservation.lines.forEach { line ->
            ensure(inventory.apply(line.productId, changed.effect, line.quantity)) { CatalogError.ConcurrentUpdate }
        }
        if (changed.effect == StockEffect.COMMIT) {
            events.committed(reservation, correlationId)
        } else {
            events.released(reservation, reason, correlationId)
        }
    }

/** `commitReservation`: legal once from `reserved`, a no-op when committed already; refused after a release. */
class CommitReservation(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        reservationId: ReservationId,
        correlationId: String? = null,
    ): Either<CatalogError, Transition> =
        catalog.resolve(
            { catalog.find(reservationId) },
            { it.commit(catalog.now()) },
            ReleaseReason.CANCELLED,
            correlationId,
        )
}

/**
 * `releaseReservation`: legal once from `reserved`, a no-op when released already; refused after a commit (restocking
 * a committed reservation follows the `OrderCancelled` event). The synchronous release carries no cause, so the
 * event reports `CANCELLED`.
 */
class ReleaseReservation(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        reservationId: ReservationId,
        correlationId: String? = null,
    ): Either<CatalogError, Transition> =
        catalog.resolve(
            { catalog.find(reservationId) },
            { it.release(catalog.now()) },
            ReleaseReason.CANCELLED,
            correlationId,
        )
}

private suspend fun Catalog.find(reservationId: ReservationId): Either<CatalogError, Reservation> =
    reservations.find(reservationId)?.right() ?: CatalogError.ReservationNotFound(reservationId).left()

/** What the order service reported about an order (events `OrderPaid`, `OrderPaymentFailed`, `OrderCancelled`). */
enum class OrderOutcome(
    val releaseReason: ReleaseReason,
) {
    PAID(ReleaseReason.CANCELLED),
    PAYMENT_FAILED(ReleaseReason.PAYMENT_FAILED),
    CANCELLED(ReleaseReason.CANCELLED),
    PAYMENT_EXPIRED(ReleaseReason.EXPIRED),
}

/** What an order event did to the order's reservation. */
sealed interface Settlement {
    /** The reservation moved (or was in the target state already: [Transition.Unchanged]). */
    data class Applied(
        val transition: Transition,
    ) : Settlement

    /** Nothing to do: no reservation for the order, or one the synchronous path already ended the other way. */
    data object Ignored : Settlement
}

/**
 * The safety net of the order events (ADR 0002): `OrderPaid` commits, `OrderPaymentFailed` releases and
 * `OrderCancelled` releases reserved stock or restocks committed stock. Every outcome is idempotent and tolerant: an
 * order without reservation, or a reservation the synchronous path already ended the other way, is ignored. Only a
 * lost race that persists is an error (the consumer retries the event).
 */
class SettleOrderReservation(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        orderId: OrderId,
        outcome: OrderOutcome,
        correlationId: String?,
    ): Either<CatalogError, Settlement> {
        val existing = catalog.reservations.findByOrder(orderId) ?: return Settlement.Ignored.right()
        val now = catalog.now()
        val result =
            catalog.resolve(
                { catalog.reservations.findByOrder(orderId)?.right() ?: notFound(existing) },
                { reservation ->
                    when (outcome) {
                        OrderOutcome.PAID -> reservation.commit(now)
                        OrderOutcome.PAYMENT_FAILED -> reservation.release(now)
                        OrderOutcome.CANCELLED, OrderOutcome.PAYMENT_EXPIRED -> reservation.cancel(now).right()
                    }
                },
                outcome.releaseReason,
                correlationId,
            )
        return result.fold(
            { failure ->
                if (failure is CatalogError.IllegalTransition) Settlement.Ignored.right() else failure.left()
            },
            { Settlement.Applied(it).right() },
        )
    }

    private fun notFound(existing: Reservation) = CatalogError.ReservationNotFound(existing.id).left()
}

/**
 * The expiry safety net: releases up to [batchSize] reservations still `reserved` after their `expiresAt`
 * (`StockReservationReleased` with reason `EXPIRED`); returns how many were released.
 */
class ExpireReservations(
    private val catalog: Catalog,
    private val batchSize: Int = DEFAULT_BATCH,
) {
    suspend operator fun invoke(): Int {
        val now = catalog.now()
        return catalog.reservations.expiring(now, batchSize).count { expired ->
            catalog
                .resolve(
                    { catalog.find(expired.id) },
                    { if (it.isExpiredAt(now)) it.release(now) else Transition.Unchanged(it).right() },
                    ReleaseReason.EXPIRED,
                    null,
                ).getOrNull() is Transition.Changed
        }
    }

    companion object {
        const val DEFAULT_BATCH: Int = 100
    }
}

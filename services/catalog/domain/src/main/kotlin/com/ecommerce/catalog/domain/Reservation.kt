package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Life cycle of a reservation: `reserved`, then `committed` (payment approved) or `released`. */
enum class ReservationState {
    RESERVED,
    COMMITTED,
    RELEASED,
}

/** Why reserved stock went back to available (events.yaml `StockReleasedPayload.reason`). */
enum class ReleaseReason {
    PAYMENT_FAILED,
    CANCELLED,
    EXPIRED,
}

/** [quantity] units of [productId] held for an order. */
data class StockLine(
    val productId: ProductId,
    val quantity: Quantity,
)

/** The result of a reservation transition: a [Changed] reservation moves stock by [Changed.effect]. */
sealed interface Transition {
    val reservation: Reservation

    /** The state changed; every line moves stock by [effect]. */
    data class Changed(
        override val reservation: Reservation,
        val effect: StockEffect,
    ) : Transition

    /** The reservation was already in the target state: nothing changes (idempotent repeat). */
    data class Unchanged(
        override val reservation: Reservation,
    ) : Transition
}

/**
 * Stock held for one order (data-model section 3.2, ADR 0002), keyed by [orderId], all or nothing across its
 * [lines]. Commit and release are legal once from `reserved` and no-ops when repeated; commit after release and
 * release after commit are refused. A cancellation ([cancel]) also ends a committed reservation by restocking.
 */
data class Reservation(
    val id: ReservationId,
    val orderId: OrderId,
    val lines: List<StockLine>,
    val state: ReservationState,
    val createdAt: Instant,
    val expiresAt: Instant,
    val resolvedAt: Instant?,
    val restocked: Boolean,
    val version: Long,
) {
    init {
        require(lines.isNotEmpty()) { "a reservation has at least one line" }
        require(lines.distinctBy(StockLine::productId).size == lines.size) { "a product appears on one line only" }
        require(expiresAt.isAfter(createdAt)) { "a reservation expires after its creation" }
    }

    /** Commits the stock after payment approval. */
    fun commit(at: Instant): Either<CatalogError, Transition> =
        when (state) {
            ReservationState.RESERVED -> resolve(ReservationState.COMMITTED, StockEffect.COMMIT, at).right()
            ReservationState.COMMITTED -> Transition.Unchanged(this).right()
            ReservationState.RELEASED -> CatalogError.IllegalTransition(id, state, "committed").left()
        }

    /** Returns the reserved stock (payment failure, expiry); a committed reservation is restocked by [cancel] only. */
    fun release(at: Instant): Either<CatalogError, Transition> =
        when (state) {
            ReservationState.RESERVED -> resolve(ReservationState.RELEASED, StockEffect.RELEASE, at).right()
            ReservationState.RELEASED -> Transition.Unchanged(this).right()
            ReservationState.COMMITTED -> CatalogError.IllegalTransition(id, state, "released").left()
        }

    /** Ends the reservation of a cancelled order: releases reserved stock, restocks committed stock. */
    fun cancel(at: Instant): Transition =
        when (state) {
            ReservationState.RESERVED -> resolve(ReservationState.RELEASED, StockEffect.RELEASE, at)
            ReservationState.COMMITTED -> resolve(ReservationState.RELEASED, StockEffect.RESTOCK, at)
            ReservationState.RELEASED -> Transition.Unchanged(this)
        }

    /** True when the reservation is still `reserved` at or after [expiresAt]. */
    fun isExpiredAt(now: Instant): Boolean = state == ReservationState.RESERVED && !now.isBefore(expiresAt)

    private fun resolve(
        target: ReservationState,
        effect: StockEffect,
        at: Instant,
    ): Transition =
        Transition.Changed(
            copy(
                state = target,
                resolvedAt = at,
                restocked = effect == StockEffect.RESTOCK,
                version = version + 1,
            ),
            effect,
        )

    companion object {
        /** Default lifetime, always later than the order's 30-minute payment expiry (service conventions 8). */
        val DEFAULT_TTL: Duration = Duration.ofMinutes(45)
        const val MAX_LINES: Int = 100

        /** Validates the requested lines: 1..100 lines, one per product, 1..99 units each; every issue is listed. */
        fun lines(requested: List<Pair<ProductId, Int>>): Either<CatalogError.Invalid, List<StockLine>> {
            val issues = mutableListOf<FieldIssue>()
            if (requested.isEmpty() || requested.size > MAX_LINES) {
                issues += FieldIssue("lines", "must contain 1 to $MAX_LINES lines")
            }
            if (requested.map { it.first }.toSet().size != requested.size) {
                issues += FieldIssue("lines", "must not repeat a productId")
            }
            val lines =
                requested.mapIndexedNotNull { index, (productId, quantity) ->
                    Quantity
                        .of(quantity)
                        .map { StockLine(productId, it) }
                        .onLeft { issues += FieldIssue("lines[$index].quantity", it.reason) }
                        .getOrNull()
                }
            return issues.toNonEmptyListOrNull()?.let { CatalogError.Invalid(it).left() } ?: lines.right()
        }

        /** A new `reserved` reservation created [at], expiring [ttl] later (whole seconds). */
        fun open(
            id: ReservationId,
            orderId: OrderId,
            lines: List<StockLine>,
            at: Instant,
            ttl: Duration = DEFAULT_TTL,
        ): Reservation {
            require(ttl >= Duration.ofSeconds(1)) { "a reservation lives at least one second" }
            val expiresAt = at.plus(ttl).truncatedTo(ChronoUnit.SECONDS)
            return Reservation(id, orderId, lines, ReservationState.RESERVED, at, expiresAt, null, false, 0)
        }

        /**
         * Every line that cannot be reserved, in request order, given what [availableOf] reports for each product (0
         * for unknown and withdrawn products, which are never reserved).
         */
        fun shortages(
            lines: List<StockLine>,
            availableOf: (ProductId) -> Int,
        ): List<UnavailableLine> =
            lines.mapNotNull { line ->
                val available = availableOf(line.productId).coerceAtLeast(0)
                val requested = line.quantity.value
                if (available < requested) UnavailableLine(line.productId, requested, available) else null
            }
    }
}

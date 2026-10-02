package com.ecommerce.order.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

/**
 * The order-status state machine of data-model section 3.4 for operator actions: placed -> preparing -> shipped ->
 * delivered, `cancelled` from placed or preparing; `preparing` only while the payment is approved.
 */
object OrderLifecycle {
    private val OPERATOR_MOVES: Map<OrderStatus, Set<OrderStatus>> =
        mapOf(
            OrderStatus.PLACED to setOf(OrderStatus.PREPARING, OrderStatus.CANCELLED),
            OrderStatus.PREPARING to setOf(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED to setOf(OrderStatus.DELIVERED),
        )

    /** True when an operator may move an order from [from] to [to], ignoring the payment guard. */
    fun isOperatorMove(
        from: OrderStatus,
        to: OrderStatus,
    ): Boolean = to in OPERATOR_MOVES[from].orEmpty()

    /** Why an operator may not move an order in [from] with [payment] to [to], or `null` when the move is legal. */
    fun refusal(
        from: OrderStatus,
        payment: PaymentStatus,
        to: OrderStatus,
    ): String? =
        when {
            !isOperatorMove(from, to) -> {
                "Cannot move an order from ${from.wire} to ${to.wire}."
            }

            to == OrderStatus.PREPARING && payment != PaymentStatus.APPROVED -> {
                "An order moves to preparing only while its payment is approved; the payment is ${payment.wire}."
            }

            else -> {
                null
            }
        }
}

/** Shopper cancellation: only while `placed` (FR-016), recorded as `SHOPPER_REQUEST`. */
fun Order.cancelByShopper(at: Instant): Either<OrderError, OrderChange> =
    if (orderStatus == OrderStatus.PLACED) {
        cancel(CancellationReason.SHOPPER_REQUEST, Actor.Shopper(accountId), at).right()
    } else {
        OrderError.NotCancellable(orderStatus).left()
    }

/** An operator moves the order to [target]; illegal moves are refused and change nothing (FR-017). */
fun Order.transition(
    target: OrderStatus,
    operator: AccountId,
    at: Instant,
): Either<OrderError, OrderChange> {
    val refusal = OrderLifecycle.refusal(orderStatus, paymentStatus, target)
    return when {
        refusal != null -> OrderError.InvalidTransition(orderStatus, target, refusal).left()
        target == OrderStatus.CANCELLED -> cancel(CancellationReason.OPERATOR, Actor.Operator(operator), at).right()
        else -> advance(target, operator, at).right()
    }
}

/** True while the stock reservation is held but not committed: the payment has not been approved yet. */
val Order.holdsUncommittedReservation: Boolean get() = paymentStatus == PaymentStatus.PENDING

/**
 * Cancels the order for [reason]. A `pending` payment is voided (`failed`, service conventions section 8), an
 * approved one stays `approved` and the payment context refunds it on `OrderCancelled`.
 */
internal fun Order.cancel(
    reason: CancellationReason,
    actor: Actor,
    at: Instant,
): OrderChange {
    val voided = paymentStatus == PaymentStatus.PENDING
    val paymentChange =
        if (voided) {
            listOf(
                StatusChange.payment(paymentStatus, PaymentStatus.FAILED, at),
            )
        } else {
            emptyList()
        }
    val cancelled =
        copy(
            orderStatus = OrderStatus.CANCELLED,
            paymentStatus = if (voided) PaymentStatus.FAILED else paymentStatus,
            paymentExpiresAt = null,
            cancellation = Cancellation(reason, at, actor.by),
            history =
                history + paymentChange + StatusChange.order(orderStatus, OrderStatus.CANCELLED, at, actor, reason),
        ).scrubIfAnonymised()
    return OrderChange(cancelled, listOf(OrderEvent.Cancelled(cancelled, at)))
}

private fun Order.advance(
    target: OrderStatus,
    operator: AccountId,
    at: Instant,
): OrderChange {
    val moved =
        copy(
            orderStatus = target,
            history = history + StatusChange.order(orderStatus, target, at, Actor.Operator(operator)),
        ).scrubIfAnonymised()
    return OrderChange(moved, listOf(OrderEvent.StatusChanged(moved, at, operator)))
}

/**
 * Account deletion (FR-007): the order keeps ids, lines, totals and statuses under [pseudonym], the recipient
 * snapshot is replaced, and the address snapshot is scrubbed once the order is terminal (now or later).
 * Applying it twice changes nothing.
 */
fun Order.anonymise(pseudonym: String): OrderChange =
    if (accountPseudonym != null) {
        unchanged()
    } else {
        OrderChange(
            copy(accountPseudonym = pseudonym, recipient = recipient.anonymised(pseudonym)).scrubIfAnonymised(),
            emptyList(),
        )
    }

/** Scrubs the address snapshot of an anonymised account's order that reached a terminal status. */
internal fun Order.scrubIfAnonymised(): Order =
    if (accountPseudonym != null && orderStatus.isTerminal) copy(deliveryAddress = deliveryAddress.scrubbed()) else this

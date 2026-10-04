package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.CancellationReason
import com.ecommerce.order.domain.Checkout
import com.ecommerce.order.domain.CheckoutRequest
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.Placement
import com.ecommerce.order.domain.Replay
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.StoredResponse
import com.ecommerce.order.domain.applyPayment
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/** A checkout request of [caller] with its `Idempotency-Key`. */
data class PlaceOrderCommand(
    val caller: Caller,
    val key: IdempotencyKey,
    val request: CheckoutRequest,
)

/** How a checkout that created an order ended (201, 202, 409 `order-cancelled` or 422 `payment-declined`). */
sealed interface CheckoutOutcome {
    val order: Order

    /** Payment approved: the order is `placed` / `approved`. */
    data class Paid(
        override val order: Order,
    ) : CheckoutOutcome

    /** Payment provider unreachable: the order is `placed` / `pending`. */
    data class AwaitingPayment(
        override val order: Order,
    ) : CheckoutOutcome

    /** Payment declined: the order is `cancelled` / `failed` with `PAYMENT_FAILED`. */
    data class Declined(
        override val order: Order,
    ) : CheckoutOutcome

    /**
     * A cancellation (shopper, operator or expiry) won the race against the charge (US4/AC2): the order is
     * `cancelled` for that reason and its pending payment voided, so the charge outcome no longer applies.
     */
    data class Cancelled(
        override val order: Order,
    ) : CheckoutOutcome

    companion object {
        /** The outcome a settled [order] stands for. */
        fun of(order: Order): CheckoutOutcome {
            val reason = order.cancellation?.reason
            return when {
                reason != null && reason != CancellationReason.PAYMENT_FAILED -> Cancelled(order)
                order.paymentStatus == PaymentStatus.APPROVED -> Paid(order)
                order.paymentStatus == PaymentStatus.PENDING -> AwaitingPayment(order)
                else -> Declined(order)
            }
        }
    }
}

/** What the caller of [PlaceOrder] answers. */
sealed interface CheckoutResult {
    /** This request ran the checkout. */
    data class Completed(
        val outcome: CheckoutOutcome,
    ) : CheckoutResult

    /** An identical earlier request ran it and created [orderId]; [response] is its stored answer. */
    data class Replayed(
        val orderId: OrderId,
        val response: StoredResponse,
    ) : CheckoutResult
}

/** The other contexts the checkout talks to, one hop each. */
data class CheckoutPorts(
    val cart: CartPort,
    val catalog: CatalogPort,
    val payment: PaymentPort,
    val accounts: AccountPort,
)

/**
 * Places an order from the shopper's cart (FR-011..FR-014, service conventions section 8): checks the cart revision,
 * freezes the current prices, reserves the stock synchronously, records the order `placed` / `pending` with
 * `OrderPlaced`, charges with the same `Idempotency-Key` and settles the payment outcome.
 *
 * The key is claimed first, so concurrent duplicates serialise on (account, key). The claim never outlives a failed
 * request (FR-013): a refusal, an exception or a cancellation before the order exists releases the stock reservation
 * and the claim. The order id is written on the claim in the transaction that stores the order, and any failure after
 * that completes the claim with the order as last known, so neither a retry with the same key nor a takeover of an
 * abandoned claim ever places a second order.
 *
 * The order's payment expires [paymentWindow] after placement (`order.payment-window`, [Order.DEFAULT_PAYMENT_WINDOW]
 * by default), when the expiry job cancels it if the payment is still pending.
 */
@Suppress("LongParameterList") // the checkout's collaborators plus its one setting, the payment window
class PlaceOrder(
    private val ports: CheckoutPorts,
    private val store: OrderStore,
    private val idempotency: IdempotencyStore,
    private val responses: CheckoutResponses,
    private val ids: OrderIds,
    private val clock: Clock,
    private val paymentWindow: Duration,
) {
    suspend operator fun invoke(command: PlaceOrderCommand): Either<OrderError, CheckoutResult> =
        if (command.caller.isShopper) {
            claimAndRun(command, command.request.fingerprint(), MAX_CLAIM_ATTEMPTS)
        } else {
            OrderError.Forbidden.left()
        }

    /**
     * Claims the key and runs the checkout; when the key is taken, replays the completed answer, refuses a different
     * request, or waits for an identical request that is still running.
     */
    private tailrec suspend fun claimAndRun(
        command: PlaceOrderCommand,
        hash: String,
        attemptsLeft: Int,
    ): Either<OrderError, CheckoutResult> {
        val now = clock.instant()
        val owned = idempotency.claim(IdempotencyRecord.claim(command.key, command.caller.accountId, hash, now), now)
        val decided =
            if (owned != null) {
                run(command, owned)
            } else {
                idempotency.find(command.caller.accountId, command.key, now)?.replayFor(hash)?.fold(
                    { it.left() },
                    { replay -> replay.completed()?.let { CheckoutResult.Replayed(it.orderId, it.response).right() } },
                )
            }
        return when {
            decided != null -> {
                decided
            }

            attemptsLeft <= 1 -> {
                OrderError.IdempotencyKeyInUse.left()
            }

            else -> {
                delay(CLAIM_POLL)
                claimAndRun(command, hash, attemptsLeft - 1)
            }
        }
    }

    /**
     * Runs the checkout under [claim] and settles the claim: an answer completes it, a refusal releases it (with the
     * reservation), and an exception or a cancellation [abandon]s it before propagating.
     */
    private suspend fun run(
        command: PlaceOrderCommand,
        claim: IdempotencyRecord,
    ): Either<OrderError, CheckoutResult> {
        val progress = Progress()
        val outcome =
            undoingOnFailure({ abandon(claim, progress) }) {
                resume(claim) ?: checkout(command, claim, progress)
            }
        return when (outcome) {
            is Either.Left -> {
                refuse(claim, progress)
                outcome
            }

            is Either.Right -> {
                idempotency.complete(claim.answeredWith(outcome.value))
                CheckoutResult.Completed(outcome.value).right()
            }
        }
    }

    /** The order an abandoned identical claim already created: a takeover answers it instead of placing another. */
    private suspend fun resume(claim: IdempotencyRecord): Either<OrderError, CheckoutOutcome>? =
        claim.orderId?.let { store.orders.findById(it) }?.let { CheckoutOutcome.of(it).right() }

    private suspend fun checkout(
        command: PlaceOrderCommand,
        claim: IdempotencyRecord,
        progress: Progress,
    ): Either<OrderError, CheckoutOutcome> =
        either {
            val placement = prepare(command, claim, progress).bind()
            val placed = Order.place(placement, paymentWindow).bind()
            store.create(placed) { idempotency.recordOrder(claim.accountId, claim.key, placement.id) }
            progress.order = placed.order
            val charge =
                ChargeRequest(
                    placement.id,
                    placement.accountId,
                    placed.order.total,
                    placement.paymentMethodRef,
                    command.key,
                )
            val settled = settle(placed.order, ports.payment.charge(charge))
            progress.order = settled
            CheckoutOutcome.of(settled).also { settleStock(it) }
        }

    /** Everything up to the reservation: cart revision, address, contact, frozen prices and the reserved stock. */
    private suspend fun prepare(
        command: PlaceOrderCommand,
        claim: IdempotencyRecord,
        progress: Progress,
    ): Either<OrderError, Placement> =
        either {
            val account = command.caller.accountId
            val cart = ports.cart.cartOf(account)?.takeIf { it.lines.isNotEmpty() } ?: raise(OrderError.EmptyCart)
            val prices = ports.catalog.pricing(cart.lines.map { it.productId })
            ensure(cart.revision == command.request.cartRevision) {
                OrderError.PriceChanged(Checkout.changedLines(cart, prices), cart.revision)
            }
            val address =
                ports.accounts.address(account, command.request.addressId) ?: raise(OrderError.AddressNotFound)
            val recipient = ports.accounts.recipient(account) ?: raise(OrderError.AccountNotFound)
            val lines = Checkout.freezeLines(cart, prices).bind()
            val orderId = ids.next()
            val reservation =
                when (val reserved = ports.catalog.reserve(orderId, Checkout.stockLines(lines))) {
                    is ReservationResult.Refused -> raise(Checkout.insufficientStock(cart, reserved.shortages))
                    is ReservationResult.Reserved -> reserved.reservationId
                }
            progress.reservation = reservation
            Placement(
                id = orderId,
                number = store.orders.nextOrderNumber(LocalDate.ofInstant(claim.createdAt, ZoneOffset.UTC)),
                accountId = account,
                lines = lines,
                deliveryAddress = address,
                recipient = recipient,
                paymentMethodRef = command.request.paymentToken,
                idempotencyKey = command.key,
                reservationId = reservation,
                placedAt = claim.createdAt,
            )
        }

    /** Applies the charge [outcome]; when the order cannot be stored (lost locks), its current state stands. */
    private suspend fun settle(
        order: Order,
        outcome: PaymentOutcome,
    ): Order =
        store
            .modify(order.id) { it.applyPayment(outcome, clock.instant()).right() }
            .fold({ store.orders.findById(order.id) ?: order }, { it.after })

    /**
     * Commits or releases the reservation and clears the cart; the order events are the safety net. A cancellation
     * released the stock itself, and a pending payment keeps it reserved.
     */
    private suspend fun settleStock(outcome: CheckoutOutcome) {
        val order = outcome.order
        when (outcome) {
            is CheckoutOutcome.Paid -> {
                ports.catalog.commit(order.reservationId)
                ports.cart.clear(order.accountId)
            }

            is CheckoutOutcome.Declined -> {
                ports.catalog.release(order.reservationId)
            }

            is CheckoutOutcome.AwaitingPayment, is CheckoutOutcome.Cancelled -> {
                Unit
            }
        }
    }

    /** A refusal before any order exists: the reservation (if any) and the claim are released, nothing is stored. */
    private suspend fun refuse(
        claim: IdempotencyRecord,
        progress: Progress,
    ) {
        progress.reservation?.let { ports.catalog.release(it) }
        idempotency.release(claim.accountId, claim.key)
    }

    /**
     * A failed request: before any order exists it is a refusal; once this request holds an order the claim is
     * completed with the answer the order stands for as last known (usually 202, the payment converging through the
     * events), so that a retry replays it. Should that write fail too, or should a takeover fail before reading the
     * order it carried over, the claim keeps the order id and the next takeover resumes that order.
     */
    private suspend fun abandon(
        claim: IdempotencyRecord,
        progress: Progress,
    ) {
        val order = progress.order
        when {
            order != null -> idempotency.complete(claim.answeredWith(CheckoutOutcome.of(order)))
            claim.orderId == null -> refuse(claim, progress)
            else -> Unit
        }
    }

    private fun IdempotencyRecord.answeredWith(outcome: CheckoutOutcome): IdempotencyRecord =
        copy(orderId = outcome.order.id, response = responses.snapshot(outcome))

    /** What a running checkout has done so far, so that a failure undoes exactly that. */
    private class Progress {
        var reservation: ReservationId? = null
        var order: Order? = null
    }

    private companion object {
        /** About five seconds of waiting for an identical request that is still running. */
        const val MAX_CLAIM_ATTEMPTS = 50
        val CLAIM_POLL = 100.milliseconds
    }
}

/**
 * Runs [block]; when it throws or is cancelled, [undo] runs (not cancellable, so a cancelled request still cleans
 * up) before the failure propagates.
 */
internal suspend fun <T> undoingOnFailure(
    undo: suspend () -> Unit,
    block: suspend () -> T,
): T {
    var finished = false
    try {
        return block().also { finished = true }
    } finally {
        if (!finished) withContext(NonCancellable) { undo() }
    }
}

private fun Replay.completed(): Replay.Completed? = this as? Replay.Completed

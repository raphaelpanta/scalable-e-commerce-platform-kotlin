package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.Checkout
import com.ecommerce.order.domain.CheckoutRequest
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.Placement
import com.ecommerce.order.domain.Replay
import com.ecommerce.order.domain.StoredResponse
import com.ecommerce.order.domain.applyPayment
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/** A checkout request of [caller] with its `Idempotency-Key`. */
data class PlaceOrderCommand(
    val caller: Caller,
    val key: IdempotencyKey,
    val request: CheckoutRequest,
)

/** How a checkout that created an order ended (201, 202 or 422 `payment-declined`). */
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

    companion object {
        /** The outcome a settled [order] stands for. */
        fun of(order: Order): CheckoutOutcome =
            when (order.paymentStatus) {
                PaymentStatus.APPROVED -> Paid(order)
                PaymentStatus.PENDING -> AwaitingPayment(order)
                PaymentStatus.FAILED -> Declined(order)
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
 * `OrderPlaced`, charges with the same `Idempotency-Key` and settles the payment outcome. The key is claimed first, so
 * concurrent duplicates serialise on (account, key); refusals release the claim and store nothing.
 */
class PlaceOrder(
    private val ports: CheckoutPorts,
    private val store: OrderStore,
    private val idempotency: IdempotencyStore,
    private val responses: CheckoutResponses,
    private val ids: OrderIds,
    private val clock: Clock,
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
        val claim = IdempotencyRecord.claim(command.key, command.caller.accountId, hash, now)
        val decided =
            if (idempotency.claim(claim, now)) {
                run(command, claim)
            } else {
                idempotency.find(claim.accountId, claim.key, now)?.replayFor(hash)?.fold(
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

    private suspend fun run(
        command: PlaceOrderCommand,
        claim: IdempotencyRecord,
    ): Either<OrderError, CheckoutResult> =
        when (val outcome = checkout(command, claim)) {
            is Either.Left -> {
                idempotency.release(claim.accountId, claim.key)
                outcome
            }

            is Either.Right -> {
                val result = outcome.value
                idempotency.complete(claim.copy(orderId = result.order.id, response = responses.snapshot(result)))
                CheckoutResult.Completed(result).right()
            }
        }

    private suspend fun checkout(
        command: PlaceOrderCommand,
        claim: IdempotencyRecord,
    ): Either<OrderError, CheckoutOutcome> =
        either {
            val placement = prepare(command, claim).bind()
            val placed = Order.place(placement).onLeft { ports.catalog.release(placement.reservationId) }.bind()
            store.create(placed)
            val charge =
                ChargeRequest(
                    placement.id,
                    placement.accountId,
                    placed.order.total,
                    placement.paymentMethodRef,
                    command.key,
                )
            val outcome = ports.payment.charge(charge)
            val settled = store.modify(placement.id) { it.applyPayment(outcome, clock.instant()).right() }.bind().after
            settleStock(settled)
            CheckoutOutcome.of(settled)
        }

    /** Everything up to the reservation: cart revision, address, contact, frozen prices and the reserved stock. */
    private suspend fun prepare(
        command: PlaceOrderCommand,
        claim: IdempotencyRecord,
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

    /** Commits or releases the reservation and clears the cart; the order events are the safety net. */
    private suspend fun settleStock(order: Order) {
        when (order.paymentStatus) {
            PaymentStatus.APPROVED -> {
                ports.catalog.commit(order.reservationId)
                ports.cart.clear(order.accountId)
            }

            PaymentStatus.FAILED -> {
                ports.catalog.release(order.reservationId)
            }

            PaymentStatus.PENDING -> {
                Unit
            }
        }
    }

    private companion object {
        /** About five seconds of waiting for an identical request that is still running. */
        const val MAX_CLAIM_ATTEMPTS = 50
        val CLAIM_POLL = 100.milliseconds
    }
}

private fun Replay.completed(): Replay.Completed? = this as? Replay.Completed

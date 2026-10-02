package com.ecommerce.order.application

import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.AddressId
import com.ecommerce.order.domain.Cart
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.Page
import com.ecommerce.order.domain.PageRequest
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.ProductPrice
import com.ecommerce.order.domain.Recipient
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.StockLine
import com.ecommerce.order.domain.StockShortage
import com.ecommerce.order.domain.StoredResponse
import java.time.Instant
import java.time.LocalDate

/** Persistence of the order aggregate (optimistic locking on [Order.version]). */
interface OrderRepository {
    /** The next order number of [day] (`ORD-<yyyyMMdd>-<sequence>`). */
    suspend fun nextOrderNumber(day: LocalDate): OrderNumber

    /** Stores a new order. */
    suspend fun insert(order: Order)

    /** Stores [order] if the stored version still equals [Order.version]; false when another change won. */
    suspend fun update(order: Order): Boolean

    suspend fun findById(id: OrderId): Order?

    /** The orders of [accountId], newest first. */
    suspend fun findByAccount(
        accountId: AccountId,
        page: PageRequest,
    ): Page<Order>

    /** Every order of [accountId] (account deletion). */
    suspend fun findAllByAccount(accountId: AccountId): List<Order>

    /** Up to [limit] placed orders whose payment is still pending at [now] after its window. */
    suspend fun findExpiredPendingPayments(
        now: Instant,
        limit: Int,
    ): List<Order>
}

/** Idempotency records of checkouts, unique per (account, key). */
interface IdempotencyStore {
    /**
     * Inserts [claim] unless a live record exists for its account and key (an expired record, or a claim abandoned
     * for longer than [IdempotencyRecord.CLAIM_TIMEOUT], is replaced); true when this request owns the key now.
     */
    suspend fun claim(
        claim: IdempotencyRecord,
        now: Instant,
    ): Boolean

    /** The live record of [key] for [accountId], if any. */
    suspend fun find(
        accountId: AccountId,
        key: IdempotencyKey,
        now: Instant,
    ): IdempotencyRecord?

    /** Completes the claim with the order and the answer. */
    suspend fun complete(record: IdempotencyRecord)

    /** Drops the claim of a refused checkout (no order, so nothing to replay). */
    suspend fun release(
        accountId: AccountId,
        key: IdempotencyKey,
    )
}

/** The cart context: read the account cart and empty it after a paid checkout. */
interface CartPort {
    /** The cart of [accountId], or null when the account has none. */
    suspend fun cartOf(accountId: AccountId): Cart?

    /** Empties the cart; failures are tolerated (the `OrderPaid` consumer of cart converges). */
    suspend fun clear(accountId: AccountId)
}

/** Outcome of a reservation request. */
sealed interface ReservationResult {
    data class Reserved(
        val reservationId: ReservationId,
    ) : ReservationResult

    data class Refused(
        val shortages: List<StockShortage>,
    ) : ReservationResult
}

/** The catalog context: prices and synchronous stock reservations (ADR 0002). */
interface CatalogPort {
    /** Current pricing of the known products among [productIds], in request order. */
    suspend fun pricing(productIds: List<ProductId>): List<ProductPrice>

    /** Reserves every line for [orderId], all or nothing. */
    suspend fun reserve(
        orderId: OrderId,
        lines: List<StockLine>,
    ): ReservationResult

    /** Commits the reservation after payment approval; failures are tolerated (`OrderPaid` converges). */
    suspend fun commit(reservationId: ReservationId)

    /** Releases the reservation; failures are tolerated (`OrderPaymentFailed` / `OrderCancelled` converge). */
    suspend fun release(reservationId: ReservationId)
}

/** A charge of an order, keyed by the checkout `Idempotency-Key`. */
data class ChargeRequest(
    val orderId: OrderId,
    val accountId: AccountId,
    val amount: Money,
    val paymentMethodRef: String,
    val idempotencyKey: IdempotencyKey,
)

/** The payment context. */
interface PaymentPort {
    /** Charges the order; an unreachable payment service is a [PaymentOutcome.Pending] without attempt. */
    suspend fun charge(request: ChargeRequest): PaymentOutcome
}

/** The identity context: snapshots copied into the order at checkout. */
interface AccountPort {
    /** The address [addressId] of [accountId], or null when unknown or owned by someone else. */
    suspend fun address(
        accountId: AccountId,
        addressId: AddressId,
    ): DeliveryAddress?

    /** The contact snapshot of [accountId], or null when the account is unknown. */
    suspend fun recipient(accountId: AccountId): Recipient?
}

/** Publishes order events through the transactional outbox; must run inside [Transactions.run]. */
interface OrderEventPublisher {
    suspend fun publish(events: List<OrderEvent>)
}

/** One database transaction around [block]. */
interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}

/** Renders the answer of a checkout, so that a replay returns it unchanged. */
fun interface CheckoutResponses {
    fun snapshot(outcome: CheckoutOutcome): StoredResponse
}

/** New identifiers. */
fun interface OrderIds {
    fun next(): OrderId
}

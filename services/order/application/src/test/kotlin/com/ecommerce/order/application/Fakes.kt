package com.ecommerce.order.application

import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.AddressId
import com.ecommerce.order.domain.Cart
import com.ecommerce.order.domain.CartLine
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.Page
import com.ecommerce.order.domain.PageRequest
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.PaymentStatus
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.ProductPrice
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.Recipient
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.StockLine
import com.ecommerce.order.domain.StoredResponse
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

val NOW: Instant = Instant.parse("2026-10-02T10:15:00Z")
val SHOPPER = AccountId(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"))
val OTHER_SHOPPER = AccountId(UUID.fromString("9e8d7c6b-5a49-4382-9f1e-0d2c4b6a8e10"))
val OPERATOR = AccountId(UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"))
val ADDRESS_ID = AddressId(UUID.fromString("5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11"))
val ADDRESS = DeliveryAddress("Ada Lovelace", "12 Analytical Street", null, "London", null, "N1 9GU", "GB")
val RECIPIENT = Recipient("ada@example.test", null, listOf(Channel.EMAIL))
val RESERVATION = ReservationId(UUID.fromString("4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15"))

fun fixedClock(at: Instant = NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

fun cartLine(
    priceMinor: Long,
    quantity: Int = 1,
    name: String = "Mug",
): CartLine =
    CartLine(
        UUID.randomUUID().toString(),
        ProductId(UUID.randomUUID()),
        "SKU-1",
        name,
        Quantity(quantity),
        Money(priceMinor, "BRL"),
    )

fun priceOf(
    line: CartLine,
    priceMinor: Long = line.priceAtAdd.amountMinor,
    active: Boolean = true,
): ProductPrice = ProductPrice(line.productId, line.sku, line.name, Money(priceMinor, "BRL"), 10, active)

class InMemoryOrders : OrderRepository {
    val stored = linkedMapOf<OrderId, Order>()
    var sequence = 0L
    var lostUpdates = 0

    override suspend fun nextOrderNumber(day: LocalDate): OrderNumber {
        yield()
        sequence += 1
        return OrderNumber.of(day, sequence)
    }

    override suspend fun insert(order: Order) {
        yield()
        stored[order.id] = order
    }

    override suspend fun update(order: Order): Boolean {
        yield()
        if (lostUpdates > 0) {
            lostUpdates--
            return false
        }
        val current = stored[order.id]
        return if (current?.version == order.version) {
            stored[order.id] = order.copy(version = order.version + 1)
            true
        } else {
            false
        }
    }

    override suspend fun findById(id: OrderId): Order? {
        yield()
        return stored[id]
    }

    override suspend fun findByAccount(
        accountId: AccountId,
        page: PageRequest,
    ): Page<Order> {
        yield()
        val mine = stored.values.filter { it.accountId == accountId }.sortedByDescending { it.placedAt }
        return Page(mine.drop(page.offset.toInt()).take(page.size), page, mine.size.toLong())
    }

    override suspend fun findAllByAccount(accountId: AccountId): List<Order> {
        yield()
        return stored.values.filter { it.accountId == accountId }
    }

    override suspend fun findExpiredPendingPayments(
        now: Instant,
        limit: Int,
    ): List<Order> {
        yield()
        return stored.values
            .filter { it.paymentStatus == PaymentStatus.PENDING && it.orderStatus == OrderStatus.PLACED }
            .filter { it.paymentExpiresAt?.isAfter(now) == false }
            .take(limit)
    }
}

class InMemoryIdempotency : IdempotencyStore {
    val records = linkedMapOf<Pair<AccountId, IdempotencyKey>, IdempotencyRecord>()
    val released = mutableListOf<IdempotencyKey>()

    /** Answers given to [find] before the stored record, to simulate a request running elsewhere. */
    val pendingFinds = ArrayDeque<IdempotencyRecord?>()

    override suspend fun claim(
        claim: IdempotencyRecord,
        now: Instant,
    ): Boolean {
        yield()
        val key = claim.accountId to claim.key
        val existing = records[key]
        return if (existing == null || existing.expiresAt.isBefore(now)) {
            records[key] = claim
            true
        } else {
            false
        }
    }

    override suspend fun find(
        accountId: AccountId,
        key: IdempotencyKey,
        now: Instant,
    ): IdempotencyRecord? {
        yield()
        return if (pendingFinds.isEmpty()) records[accountId to key] else pendingFinds.removeFirst()
    }

    override suspend fun complete(record: IdempotencyRecord) {
        yield()
        records[record.accountId to record.key] = record
    }

    override suspend fun release(
        accountId: AccountId,
        key: IdempotencyKey,
    ) {
        yield()
        released += key
        records.remove(accountId to key)
    }
}

class FakeCart(
    var cart: Cart?,
) : CartPort {
    val cleared = mutableListOf<AccountId>()

    override suspend fun cartOf(accountId: AccountId): Cart? {
        yield()
        return cart
    }

    override suspend fun clear(accountId: AccountId) {
        yield()
        cleared += accountId
    }
}

class FakeCatalog(
    var prices: List<ProductPrice> = emptyList(),
    var reservation: ReservationResult = ReservationResult.Reserved(RESERVATION),
) : CatalogPort {
    val reserved = mutableListOf<Pair<OrderId, List<StockLine>>>()
    val committed = mutableListOf<ReservationId>()
    val released = mutableListOf<ReservationId>()
    val pricingRequests = mutableListOf<List<ProductId>>()

    override suspend fun pricing(productIds: List<ProductId>): List<ProductPrice> {
        yield()
        pricingRequests += productIds
        return prices.filter { it.productId in productIds }
    }

    override suspend fun reserve(
        orderId: OrderId,
        lines: List<StockLine>,
    ): ReservationResult {
        yield()
        reserved += orderId to lines
        return reservation
    }

    override suspend fun commit(reservationId: ReservationId) {
        yield()
        committed += reservationId
    }

    override suspend fun release(reservationId: ReservationId) {
        yield()
        released += reservationId
    }
}

class FakePayment(
    var outcome: PaymentOutcome,
) : PaymentPort {
    val charges = mutableListOf<ChargeRequest>()

    override suspend fun charge(request: ChargeRequest): PaymentOutcome {
        yield()
        charges += request
        return outcome
    }
}

class FakeAccounts(
    var address: DeliveryAddress? = ADDRESS,
    var recipient: Recipient? = RECIPIENT,
) : AccountPort {
    val addressRequests = mutableListOf<Pair<AccountId, AddressId>>()

    override suspend fun address(
        accountId: AccountId,
        addressId: AddressId,
    ): DeliveryAddress? {
        yield()
        addressRequests += accountId to addressId
        return address
    }

    override suspend fun recipient(accountId: AccountId): Recipient? {
        yield()
        return recipient
    }
}

class RecordingEvents : OrderEventPublisher {
    val published = mutableListOf<OrderEvent>()

    override suspend fun publish(events: List<OrderEvent>) {
        yield()
        published += events
    }
}

class DirectTransactions : Transactions {
    var count = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        yield()
        count++
        return block()
    }
}

/** Renders a checkout as `<status>:<order id>` so that tests can check what was stored. */
val TEST_RESPONSES =
    CheckoutResponses { outcome ->
        val status =
            when (outcome) {
                is CheckoutOutcome.Paid -> 201
                is CheckoutOutcome.AwaitingPayment -> 202
                is CheckoutOutcome.Declined -> 422
            }
        StoredResponse(status, outcome.order.id.toString())
    }

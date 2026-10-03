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

/** What an adapter throws when its dependency fails: a 503 problem of a client or a database error. */
class DependencyFailure(
    what: String,
) : RuntimeException(what)

/** The port calls a test can make fail, in checkout order. */
enum class Step {
    CART,
    PRICING,
    ADDRESS,
    RECIPIENT,
    RESERVE,
    FIND_ORDER,
    ORDER_NUMBER,
    INSERT,
    RECORD_ORDER,
    CHARGE,
    UPDATE,
    COMMIT,
    COMPLETE,
}

/** Throws a [DependencyFailure] when [step] is among [failing]. */
fun Set<Step>.check(step: Step) {
    if (step in this) throw DependencyFailure("$step failed")
}

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
    var failing: Set<Step> = emptySet()

    override suspend fun nextOrderNumber(day: LocalDate): OrderNumber {
        yield()
        failing.check(Step.ORDER_NUMBER)
        sequence += 1
        return OrderNumber.of(day, sequence)
    }

    override suspend fun insert(order: Order) {
        yield()
        failing.check(Step.INSERT)
        stored[order.id] = order
    }

    override suspend fun update(order: Order): Boolean {
        yield()
        failing.check(Step.UPDATE)
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
        failing.check(Step.FIND_ORDER)
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
    var failing: Set<Step> = emptySet()

    /** Answers given to [find] before the stored record, to simulate a request running elsewhere. */
    val pendingFinds = ArrayDeque<IdempotencyRecord?>()
    var finds = 0

    /** Same rules as the R2DBC upsert: expired records are replaced, abandoned claims taken over. */
    override suspend fun claim(
        claim: IdempotencyRecord,
        now: Instant,
    ): IdempotencyRecord? {
        yield()
        val key = claim.accountId to claim.key
        val existing = records[key]
        val owned =
            when {
                existing == null || !existing.expiresAt.isAfter(now) -> {
                    claim
                }

                existing.abandonedAt(
                    now,
                ) && (existing.orderId == null || existing.requestHash == claim.requestHash) -> {
                    claim.copy(orderId = existing.orderId)
                }

                else -> {
                    null
                }
            }
        owned?.let { records[key] = it }
        return owned
    }

    private fun IdempotencyRecord.abandonedAt(now: Instant): Boolean =
        response == null && createdAt.isBefore(now.minus(IdempotencyRecord.CLAIM_TIMEOUT))

    override suspend fun find(
        accountId: AccountId,
        key: IdempotencyKey,
        now: Instant,
    ): IdempotencyRecord? {
        yield()
        finds++
        return if (pendingFinds.isEmpty()) records[accountId to key] else pendingFinds.removeFirst()
    }

    override suspend fun recordOrder(
        accountId: AccountId,
        key: IdempotencyKey,
        orderId: OrderId,
    ) {
        yield()
        failing.check(Step.RECORD_ORDER)
        records.computeIfPresent(accountId to key) { _, record -> record.copy(orderId = orderId) }
    }

    override suspend fun complete(record: IdempotencyRecord) {
        yield()
        failing.check(Step.COMPLETE)
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

    override suspend fun purgeExpired(now: Instant): Long {
        yield()
        val expired = records.filterValues { !it.expiresAt.isAfter(now) }.keys
        expired.forEach(records::remove)
        return expired.size.toLong()
    }
}

class FakeCart(
    var cart: Cart?,
) : CartPort {
    val cleared = mutableListOf<AccountId>()
    var failing: Set<Step> = emptySet()

    override suspend fun cartOf(accountId: AccountId): Cart? {
        yield()
        failing.check(Step.CART)
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
    var failing: Set<Step> = emptySet()

    /** Runs while a reservation is in flight, before catalog answers. */
    var reserving: suspend () -> Unit = {}

    override suspend fun pricing(productIds: List<ProductId>): List<ProductPrice> {
        yield()
        failing.check(Step.PRICING)
        pricingRequests += productIds
        return prices.filter { it.productId in productIds }
    }

    override suspend fun reserve(
        orderId: OrderId,
        lines: List<StockLine>,
    ): ReservationResult {
        yield()
        reserving()
        failing.check(Step.RESERVE)
        reserved += orderId to lines
        return reservation
    }

    override suspend fun commit(reservationId: ReservationId) {
        yield()
        failing.check(Step.COMMIT)
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
    var failing: Set<Step> = emptySet()

    /** Runs while the charge is in flight, before its outcome comes back (a cancellation racing it, say). */
    var meanwhile: suspend (ChargeRequest) -> Unit = {}

    override suspend fun charge(request: ChargeRequest): PaymentOutcome {
        yield()
        charges += request
        meanwhile(request)
        failing.check(Step.CHARGE)
        return outcome
    }
}

class FakeAccounts(
    var address: DeliveryAddress? = ADDRESS,
    var recipient: Recipient? = RECIPIENT,
) : AccountPort {
    val addressRequests = mutableListOf<Pair<AccountId, AddressId>>()
    var failing: Set<Step> = emptySet()

    override suspend fun address(
        accountId: AccountId,
        addressId: AddressId,
    ): DeliveryAddress? {
        yield()
        failing.check(Step.ADDRESS)
        addressRequests += accountId to addressId
        return address
    }

    override suspend fun recipient(accountId: AccountId): Recipient? {
        yield()
        failing.check(Step.RECIPIENT)
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

/** Runs the block directly; with [orders] and [events] a failing block is rolled back, as a database would. */
class DirectTransactions(
    private val orders: InMemoryOrders? = null,
    private val events: RecordingEvents? = null,
) : Transactions {
    var count = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        yield()
        count++
        val stored = orders?.stored?.toMap()
        val published = events?.published?.toList()
        var committed = false
        try {
            return block().also { committed = true }
        } finally {
            if (!committed) rollBack(stored, published)
        }
    }

    private fun rollBack(
        stored: Map<OrderId, Order>?,
        published: List<OrderEvent>?,
    ) {
        if (orders != null && stored != null) {
            orders.stored.clear()
            orders.stored.putAll(stored)
        }
        if (events != null && published != null) {
            events.published.clear()
            events.published.addAll(published)
        }
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
                is CheckoutOutcome.Cancelled -> 409
            }
        StoredResponse(status, outcome.order.id.toString())
    }

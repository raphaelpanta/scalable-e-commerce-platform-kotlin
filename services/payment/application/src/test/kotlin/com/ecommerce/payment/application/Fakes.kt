package com.ecommerce.payment.application

import arrow.core.getOrElse
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.PaymentProviderPort
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.ProviderReference
import com.ecommerce.payment.domain.Recipient
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

val NOW: Instant = Instant.parse("2026-10-02T10:15:01Z")
val ADA = AccountId(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"))
val GRACE = AccountId(UUID.fromString("3b5d7f91-2c4e-4a68-9b0d-1f3a5c7e9b24"))
val OPERATOR_ID = AccountId(UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"))
val ADA_CONTACT = Recipient(ADA, "ada@example.test", null, listOf("email"))
val CHARGE_REF = ProviderReference("sim_ch_000123")
val REFUND_REF = ProviderReference("sim_rf_000045")
const val APPROVE_TOKEN = "tok_sim_approve_4242"

fun fixedClock(at: Instant = NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

fun brl(amountMinor: Long): Money = Money(amountMinor, "BRL")

fun token(value: String = APPROVE_TOKEN): PaymentMethodRef = PaymentMethodRef.of(value).getOrElse { error(it) }

fun newKey(): IdempotencyKey = IdempotencyKey(UUID.randomUUID())

fun newOrder(): OrderId = OrderId(UUID.randomUUID())

fun chargeRequest(
    orderId: OrderId = newOrder(),
    amountMinor: Long = 19_800,
    key: IdempotencyKey = newKey(),
    owner: AccountId = ADA,
): ChargeRequest = ChargeRequest(orderId, owner, brl(amountMinor), token(), key)

fun attemptFor(
    request: ChargeRequest,
    decision: ProviderDecision = ProviderDecision.Approved(CHARGE_REF),
    at: Instant = NOW,
): PaymentAttempt = PaymentAttempt.charge(PaymentAttemptId(UUID.randomUUID()), request, decision, at)

class InMemoryAttempts : PaymentAttemptRepository {
    val stored = linkedMapOf<PaymentAttemptId, PaymentAttempt>()

    /** Stored by "another request" right before the next insert, to simulate a concurrent writer. */
    var concurrent: PaymentAttempt? = null

    override suspend fun insert(attempt: PaymentAttempt): Boolean {
        yield()
        concurrent?.let { stored[it.id] = it }
        concurrent = null
        val duplicateKey = stored.values.any { it.idempotencyKey == attempt.idempotencyKey }
        val secondApproval =
            attempt.outcome == PaymentOutcome.APPROVED && stored.values.any { it.isApprovedChargeOf(attempt.orderId) }
        return if (duplicateKey || secondApproval) {
            false
        } else {
            stored[attempt.id] = attempt
            true
        }
    }

    override suspend fun findById(id: PaymentAttemptId): PaymentAttempt? {
        yield()
        return stored[id]
    }

    override suspend fun findByKey(key: IdempotencyKey): PaymentAttempt? {
        yield()
        return stored.values.firstOrNull { it.idempotencyKey == key }
    }

    override suspend fun findApprovedCharge(orderId: OrderId): PaymentAttempt? {
        yield()
        return stored.values.firstOrNull { it.isApprovedChargeOf(orderId) }
    }

    override suspend fun ownerOf(orderId: OrderId): AccountId? {
        yield()
        return stored.values.firstOrNull { it.orderId == orderId }?.accountId
    }

    override suspend fun findByOrder(
        orderId: OrderId,
        page: PageRequest,
    ): Page<PaymentAttempt> {
        yield()
        val all = stored.values.filter { it.orderId == orderId }.sortedByDescending { it.createdAt }
        return Page(all.drop(page.offset.toInt()).take(page.size), page, all.size.toLong())
    }
}

class InMemoryRefunds : RefundRepository {
    val stored = linkedMapOf<RefundId, RefundRecord>()

    /** Stored by "another request" right before the next insert. */
    var concurrent: RefundRecord? = null

    /** When true, the next markAnnounced loses to a concurrent consumer. */
    var announcedElsewhere = false

    override suspend fun insert(refund: RefundRecord): Boolean {
        yield()
        concurrent?.let { stored[it.id] = it }
        concurrent = null
        val taken = stored.values.any { it.idempotencyKey == refund.idempotencyKey || it.attemptId == refund.attemptId }
        if (!taken) stored[refund.id] = refund
        return !taken
    }

    override suspend fun findById(id: RefundId): RefundRecord? {
        yield()
        return stored[id]
    }

    override suspend fun findByKey(key: IdempotencyKey): RefundRecord? {
        yield()
        return stored.values.firstOrNull { it.idempotencyKey == key }
    }

    override suspend fun findByAttempt(attemptId: PaymentAttemptId): RefundRecord? {
        yield()
        return stored.values.firstOrNull { it.attemptId == attemptId }
    }

    override suspend fun findByOrder(
        orderId: OrderId,
        page: PageRequest,
    ): Page<RefundRecord> {
        yield()
        val all = stored.values.filter { it.orderId == orderId }.sortedByDescending { it.createdAt }
        return Page(all.drop(page.offset.toInt()).take(page.size), page, all.size.toLong())
    }

    override suspend fun markAnnounced(
        id: RefundId,
        at: Instant,
    ): Boolean {
        yield()
        val refund = stored[id]
        return if (refund == null || refund.announcedAt != null || announcedElsewhere) {
            false
        } else {
            stored[id] = refund.copy(announcedAt = at)
            true
        }
    }
}

class FakeProvider(
    var decision: ProviderDecision = ProviderDecision.Approved(CHARGE_REF),
) : PaymentProviderPort {
    val charges = mutableListOf<Pair<PaymentMethodRef, Money>>()
    val refunds = mutableListOf<Pair<ProviderReference, Money>>()

    override suspend fun charge(
        paymentMethod: PaymentMethodRef,
        amount: Money,
    ): ProviderDecision {
        yield()
        charges += paymentMethod to amount
        return decision
    }

    override suspend fun refund(
        charge: ProviderReference,
        amount: Money,
    ): ProviderReference {
        yield()
        refunds += charge to amount
        return REFUND_REF
    }
}

class RecordingEvents : PaymentEventPublisher {
    val published = mutableListOf<PaymentEvent>()

    override suspend fun publish(event: PaymentEvent) {
        yield()
        published += event
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

class SequentialIds : PaymentIds {
    val attempts = mutableListOf<PaymentAttemptId>()
    val refunds = mutableListOf<RefundId>()

    override fun nextAttempt(): PaymentAttemptId = PaymentAttemptId(UUID.randomUUID()).also { attempts += it }

    override fun nextRefund(): RefundId = RefundId(UUID.randomUUID()).also { refunds += it }
}

/** Every fake wired into the use cases, as the infrastructure wires the adapters. */
class Backend {
    val attempts = InMemoryAttempts()
    val refunds = InMemoryRefunds()
    val events = RecordingEvents()
    val transactions = DirectTransactions()
    val provider = FakeProvider()
    val ids = SequentialIds()
    val clock: Clock = fixedClock()
    val ledger = PaymentLedger(attempts, refunds, events, transactions)
    val authoriseCharge = AuthoriseCharge(ledger, provider, ids, clock)
    val recordRefund = RecordRefund(ledger, provider, ids, clock)
    val chargePlacedOrder = ChargePlacedOrder(authoriseCharge)
    val refundCancelledOrder = RefundCancelledOrder(ledger, recordRefund, clock)

    fun stored(attempt: PaymentAttempt): PaymentAttempt = attempt.also { attempts.stored[it.id] = it }

    fun stored(refund: RefundRecord): RefundRecord = refund.also { refunds.stored[it.id] = it }

    fun approvedCharge(
        orderId: OrderId = newOrder(),
        owner: AccountId = ADA,
    ): PaymentAttempt = stored(attemptFor(chargeRequest(orderId, owner = owner)))
}

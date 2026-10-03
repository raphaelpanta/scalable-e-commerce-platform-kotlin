package com.ecommerce.payment.infrastructure.persistence

import com.ecommerce.payment.application.PaymentAttemptRepository
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentOutcome
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import java.time.Instant
import java.util.UUID

/**
 * Charge attempts in `payment_attempts`. The insert does nothing on a unique conflict, the idempotency key, the
 * one-approved-charge-per-order index or the one-retry-per-attempt constraint, so two concurrent requests serialise on
 * the constraint: one stores its attempt, the other learns that it lost and reads the winner back.
 */
class R2dbcPaymentAttemptRepository(
    private val database: DatabaseClient,
) : PaymentAttemptRepository {
    override suspend fun insert(attempt: PaymentAttempt): Boolean =
        database
            .sql(INSERT)
            .bind("id", attempt.id.value)
            .bind("orderId", attempt.orderId.value)
            .bind("accountId", attempt.accountId.value)
            .bind("kind", attempt.kind.wire)
            .bind("amountMinor", attempt.amount.amountMinor)
            .bind("currency", attempt.amount.currency)
            .bind("paymentMethodRef", attempt.paymentMethodRef.token)
            .bind("outcome", attempt.outcome.wire)
            .bindNullable("declineCategory", attempt.declineCategory?.wire, String::class.java)
            .bindNullable("providerReference", attempt.providerReference?.value, String::class.java)
            .bind("idempotencyKey", attempt.idempotencyKey.value)
            .bind("createdAt", attempt.createdAt)
            .bind("attemptNumber", attempt.attemptNumber)
            .bindNullable("previousAttemptId", attempt.previousAttemptId?.value, UUID::class.java)
            .awaitRowsUpdated() == 1L

    override suspend fun findById(id: PaymentAttemptId): PaymentAttempt? = one("id = :value", id.value)

    override suspend fun findByKey(key: IdempotencyKey): PaymentAttempt? = one("idempotency_key = :value", key.value)

    override suspend fun findApprovedCharge(orderId: OrderId): PaymentAttempt? =
        database
            .sql("$SELECT WHERE order_id = :orderId AND kind = 'charge' AND outcome = :approved")
            .bind("orderId", orderId.value)
            .bind("approved", PaymentOutcome.APPROVED.wire)
            .map { row, _ -> row.toAttempt() }
            .awaitOneOrNull()

    override suspend fun ownerOf(orderId: OrderId): AccountId? =
        database
            .sql("SELECT account_id FROM payment_attempts WHERE order_id = :orderId LIMIT 1")
            .bind("orderId", orderId.value)
            .map { row, _ -> AccountId(checkNotNull(row.get("account_id", UUID::class.java))) }
            .awaitOneOrNull()

    override suspend fun findByOrder(
        orderId: OrderId,
        page: PageRequest,
    ): Page<PaymentAttempt> {
        val items =
            database
                .sql(PAGE_OF_ORDER)
                .bind("orderId", orderId.value)
                .bind("limit", page.size)
                .bind("offset", page.offset)
                .map { row, _ -> row.toAttempt() }
                .all()
                .collectList()
                .awaitSingle()
        val total =
            database
                .sql("SELECT count(*) AS total FROM payment_attempts WHERE order_id = :orderId")
                .bind("orderId", orderId.value)
                .map { row, _ -> row.count() }
                .one()
                .awaitSingle()
        return Page(items, page, total)
    }

    override suspend fun findPendingOf(orderId: OrderId): List<PaymentAttempt> =
        database
            .sql("$SELECT WHERE order_id = :orderId AND outcome = :pending ORDER BY created_at, id")
            .bind("orderId", orderId.value)
            .bind("pending", PaymentOutcome.PENDING.wire)
            .map { row, _ -> row.toAttempt() }
            .all()
            .collectList()
            .awaitSingle()

    override suspend fun findDueForRetry(
        createdUpTo: Instant,
        maxAttempts: Int,
        limit: Int,
    ): List<PaymentAttempt> =
        database
            .sql(
                "$SELECT WHERE outcome = :pending AND created_at <= :createdUpTo AND attempt_number < :maxAttempts " +
                    "ORDER BY created_at, id LIMIT :limit",
            ).bind("pending", PaymentOutcome.PENDING.wire)
            .bind("createdUpTo", createdUpTo)
            .bind("maxAttempts", maxAttempts)
            .bind("limit", limit)
            .map { row, _ -> row.toAttempt() }
            .all()
            .collectList()
            .awaitSingle()

    override suspend fun markVoided(id: PaymentAttemptId): Boolean =
        database
            .sql("UPDATE payment_attempts SET outcome = :voided WHERE id = :id AND outcome = :pending")
            .bind("voided", PaymentOutcome.VOIDED.wire)
            .bind("id", id.value)
            .bind("pending", PaymentOutcome.PENDING.wire)
            .awaitRowsUpdated() == 1L

    private suspend fun one(
        condition: String,
        value: UUID,
    ): PaymentAttempt? =
        database
            .sql("$SELECT WHERE $condition")
            .bind("value", value)
            .map { row, _ -> row.toAttempt() }
            .awaitOneOrNull()

    private companion object {
        const val ORDER_PAGE_CLAUSE =
            " WHERE order_id = :orderId ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"
        const val SELECT =
            "SELECT id, order_id, account_id, amount_minor, currency, payment_method_ref, outcome, decline_category, " +
                "provider_reference, idempotency_key, created_at, attempt_number, previous_attempt_id " +
                "FROM payment_attempts"
        const val PAGE_OF_ORDER = SELECT + ORDER_PAGE_CLAUSE
        const val INSERT =
            "INSERT INTO payment_attempts (id, order_id, account_id, kind, amount_minor, currency, " +
                "payment_method_ref, outcome, decline_category, provider_reference, idempotency_key, created_at, " +
                "attempt_number, previous_attempt_id) VALUES (:id, :orderId, :accountId, :kind, :amountMinor, " +
                ":currency, :paymentMethodRef, :outcome, :declineCategory, :providerReference, :idempotencyKey, " +
                ":createdAt, :attemptNumber, :previousAttemptId) ON CONFLICT DO NOTHING"
    }
}

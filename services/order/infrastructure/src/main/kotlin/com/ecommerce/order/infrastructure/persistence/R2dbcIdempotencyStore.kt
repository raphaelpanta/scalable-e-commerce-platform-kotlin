package com.ecommerce.order.infrastructure.persistence

import com.ecommerce.order.application.IdempotencyStore
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.StoredResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Idempotency records in `idempotency_records`, unique per (account, key). The claim is an upsert that only takes
 * over an expired record or an abandoned claim, so two concurrent requests with the same key serialise on the
 * primary key: exactly one of them inserts (or takes over), the other reads the record back. Taking over an
 * abandoned claim keeps the order it created, and a claim with an order is only taken over by the same request.
 */
class R2dbcIdempotencyStore(
    private val database: DatabaseClient,
) : IdempotencyStore {
    override suspend fun claim(
        claim: IdempotencyRecord,
        now: Instant,
    ): IdempotencyRecord? =
        database
            .sql(CLAIM)
            .bind("accountId", claim.accountId.value)
            .bind("key", claim.key.value)
            .bind("hash", claim.requestHash)
            .bind("createdAt", claim.createdAt)
            .bind("expiresAt", claim.expiresAt)
            .bind("now", now)
            .bind("abandonedBefore", now.minus(IdempotencyRecord.CLAIM_TIMEOUT))
            .map { row, _ -> Optional.ofNullable(row.get("order_id", UUID::class.java)) }
            .awaitOneOrNull()
            ?.let { carried -> claim.copy(orderId = carried.map(::OrderId).orElse(null)) }

    override suspend fun find(
        accountId: AccountId,
        key: IdempotencyKey,
        now: Instant,
    ): IdempotencyRecord? =
        database
            .sql(
                "SELECT * FROM idempotency_records WHERE account_id = :accountId AND idempotency_key = :key " +
                    "AND expires_at > :now",
            ).bind("accountId", accountId.value)
            .bind("key", key.value)
            .bind("now", now)
            .map { row, _ ->
                val status = row.optional<Int>("response_status")
                val body = row.optional<String>("response_body")
                IdempotencyRecord(
                    key = key,
                    accountId = accountId,
                    requestHash = row.required("request_hash"),
                    orderId = row.optional<UUID>("order_id")?.let(::OrderId),
                    response = if (status != null && body != null) StoredResponse(status, body) else null,
                    createdAt = row.required("created_at"),
                    expiresAt = row.required("expires_at"),
                )
            }.awaitOneOrNull()

    override suspend fun recordOrder(
        accountId: AccountId,
        key: IdempotencyKey,
        orderId: OrderId,
    ) {
        database
            .sql(
                "UPDATE idempotency_records SET order_id = :orderId " +
                    "WHERE account_id = :accountId AND idempotency_key = :key",
            ).bind("orderId", orderId.value)
            .bind("accountId", accountId.value)
            .bind("key", key.value)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    override suspend fun complete(record: IdempotencyRecord) {
        database
            .sql(
                "UPDATE idempotency_records SET order_id = :orderId, response_status = :status, " +
                    "response_body = :body WHERE account_id = :accountId AND idempotency_key = :key",
            ).bindNullable("orderId", record.orderId?.value, UUID::class.java)
            .bindNullable("status", record.response?.status, Int::class.javaObjectType)
            .bindNullable("body", record.response?.body, String::class.java)
            .bind("accountId", record.accountId.value)
            .bind("key", record.key.value)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    override suspend fun release(
        accountId: AccountId,
        key: IdempotencyKey,
    ) {
        database
            .sql("DELETE FROM idempotency_records WHERE account_id = :accountId AND idempotency_key = :key")
            .bind("accountId", accountId.value)
            .bind("key", key.value)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** One statement, never cancelled half-way (as the outbox purge of platform-messaging). */
    override suspend fun purgeExpired(now: Instant): Long =
        withContext(NonCancellable) {
            database
                .sql("DELETE FROM idempotency_records WHERE expires_at <= :now")
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    private companion object {
        const val CLAIM =
            "INSERT INTO idempotency_records (account_id, idempotency_key, request_hash, created_at, expires_at) " +
                "VALUES (:accountId, :key, :hash, :createdAt, :expiresAt) " +
                "ON CONFLICT (account_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash, " +
                "order_id = CASE WHEN idempotency_records.expires_at <= :now THEN NULL " +
                "ELSE idempotency_records.order_id END, response_status = NULL, response_body = NULL, " +
                "created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at " +
                "WHERE idempotency_records.expires_at <= :now " +
                "OR (idempotency_records.response_status IS NULL " +
                "AND idempotency_records.created_at < :abandonedBefore " +
                "AND (idempotency_records.order_id IS NULL " +
                "OR idempotency_records.request_hash = EXCLUDED.request_hash)) " +
                "RETURNING order_id"
    }
}

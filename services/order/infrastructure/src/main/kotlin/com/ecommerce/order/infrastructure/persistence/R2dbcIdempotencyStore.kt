package com.ecommerce.order.infrastructure.persistence

import com.ecommerce.order.application.IdempotencyStore
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.IdempotencyRecord
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.StoredResponse
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import java.time.Instant
import java.util.UUID

/**
 * Idempotency records in `idempotency_records`, unique per (account, key). The claim is an upsert that only takes
 * over an expired record or an abandoned claim, so two concurrent requests with the same key serialise on the
 * primary key: exactly one of them inserts, the other reads the record back.
 */
class R2dbcIdempotencyStore(
    private val database: DatabaseClient,
) : IdempotencyStore {
    override suspend fun claim(
        claim: IdempotencyRecord,
        now: Instant,
    ): Boolean =
        database
            .sql(CLAIM)
            .bind("accountId", claim.accountId.value)
            .bind("key", claim.key.value)
            .bind("hash", claim.requestHash)
            .bind("createdAt", claim.createdAt)
            .bind("expiresAt", claim.expiresAt)
            .bind("now", now)
            .bind("abandonedBefore", now.minus(IdempotencyRecord.CLAIM_TIMEOUT))
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

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

    private companion object {
        const val CLAIM =
            "INSERT INTO idempotency_records (account_id, idempotency_key, request_hash, created_at, expires_at) " +
                "VALUES (:accountId, :key, :hash, :createdAt, :expiresAt) " +
                "ON CONFLICT (account_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash, " +
                "order_id = NULL, response_status = NULL, response_body = NULL, " +
                "created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at " +
                "WHERE idempotency_records.expires_at <= :now " +
                "OR (idempotency_records.response_status IS NULL AND idempotency_records.created_at < :abandonedBefore)"
    }
}

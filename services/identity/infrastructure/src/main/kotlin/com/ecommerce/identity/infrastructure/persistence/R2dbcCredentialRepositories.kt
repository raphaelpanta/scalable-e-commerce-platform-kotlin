package com.ecommerce.identity.infrastructure.persistence

import com.ecommerce.identity.application.SessionRepository
import com.ecommerce.identity.application.SourceThrottleRepository
import com.ecommerce.identity.application.TokenRepository
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.Digests
import com.ecommerce.identity.domain.OneTimeToken
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.domain.SessionRecord
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.TokenHash
import com.ecommerce.identity.domain.TokenPurpose
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Outbound adapter: the `one_time_token` table (hashes only). */
class R2dbcTokenRepository(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
) : TokenRepository {
    override suspend fun issue(token: OneTimeToken) {
        transactions.executeAndAwait {
            database
                .sql(
                    "UPDATE one_time_token SET used_at = :now " +
                        "WHERE account_id = :accountId AND purpose = :purpose AND used_at IS NULL",
                ).bind("now", token.issuedAt)
                .bind("accountId", token.accountId.value)
                .bind("purpose", token.purpose.code)
                .fetch()
                .awaitRowsUpdated()
            database
                .sql(
                    "INSERT INTO one_time_token (token_hash, account_id, purpose, issued_at, expires_at, used_at) " +
                        "VALUES (:hash, :accountId, :purpose, :issuedAt, :expiresAt, NULL)",
                ).bind("hash", token.hash.value)
                .bind("accountId", token.accountId.value)
                .bind("purpose", token.purpose.code)
                .bind("issuedAt", token.issuedAt)
                .bind("expiresAt", token.expiresAt)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    override suspend fun find(
        purpose: TokenPurpose,
        hash: TokenHash,
    ): OneTimeToken? =
        database
            .sql(
                "SELECT account_id, issued_at, expires_at, used_at FROM one_time_token " +
                    "WHERE token_hash = :hash AND purpose = :purpose",
            ).bind("hash", hash.value)
            .bind("purpose", purpose.code)
            .map { row ->
                OneTimeToken(
                    AccountId(row.required<UUID>("account_id")),
                    purpose,
                    hash,
                    row.required("issued_at"),
                    row.required("expires_at"),
                    row.optional("used_at"),
                )
            }.awaitOneOrNull()

    override suspend fun markUsed(used: OneTimeToken): Boolean =
        database
            .sql("UPDATE one_time_token SET used_at = :usedAt WHERE token_hash = :hash AND used_at IS NULL")
            .bind("usedAt", checkNotNull(used.usedAt) { "a used token carries its use time" })
            .bind("hash", used.hash.value)
            .fetch()
            .awaitRowsUpdated() == 1L

    override suspend fun invalidateAll(
        accountId: AccountId,
        now: Instant,
    ) {
        database
            .sql("UPDATE one_time_token SET used_at = :now WHERE account_id = :accountId AND used_at IS NULL")
            .bind("now", now)
            .bind("accountId", accountId.value)
            .fetch()
            .awaitRowsUpdated()
    }
}

/** Outbound adapter: the `account_session` and `session_refresh_token` tables (hashes only). */
class R2dbcSessionRepository(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
) : SessionRepository {
    override suspend fun insert(session: SessionRecord) {
        transactions.executeAndAwait {
            database
                .sql(
                    "INSERT INTO account_session (id, account_id, refresh_token_hash, issued_at, expires_at, " +
                        "rotated_at, revoked_at) VALUES (:id, :accountId, :hash, :issuedAt, :expiresAt, NULL, NULL)",
                ).bind("id", session.id.value)
                .bind("accountId", session.accountId.value)
                .bind("hash", session.refreshTokenHash.value)
                .bind("issuedAt", session.issuedAt)
                .bind("expiresAt", session.expiresAt)
                .fetch()
                .awaitRowsUpdated()
            rememberRefreshToken(session)
        }
    }

    override suspend fun find(id: SessionId): SessionRecord? =
        database
            .sql("SELECT $COLUMNS FROM account_session WHERE id = :id")
            .bind("id", id.value)
            .map(::sessionOf)
            .awaitOneOrNull()

    override suspend fun findByRefreshHash(hash: TokenHash): SessionRecord? =
        database
            .sql(
                "SELECT ${COLUMNS.split(", ").joinToString(", ") { "s.$it" }} FROM account_session s " +
                    "JOIN session_refresh_token t ON t.session_id = s.id WHERE t.token_hash = :hash",
            ).bind("hash", hash.value)
            .map(::sessionOf)
            .awaitOneOrNull()

    override suspend fun rotate(
        rotated: SessionRecord,
        previous: TokenHash,
    ): Boolean =
        transactions.executeAndAwait {
            val updated =
                database
                    .sql(
                        "UPDATE account_session SET refresh_token_hash = :hash, rotated_at = :rotatedAt " +
                            "WHERE id = :id AND refresh_token_hash = :previous AND revoked_at IS NULL",
                    ).bind("hash", rotated.refreshTokenHash.value)
                    .bindNullable("rotatedAt", rotated.rotatedAt, Instant::class.java)
                    .bind("id", rotated.id.value)
                    .bind("previous", previous.value)
                    .fetch()
                    .awaitRowsUpdated() == 1L
            if (updated) rememberRefreshToken(rotated)
            updated
        }

    override suspend fun revoke(
        id: SessionId,
        now: Instant,
    ) {
        database
            .sql("UPDATE account_session SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
            .bind("now", now)
            .bind("id", id.value)
            .fetch()
            .awaitRowsUpdated()
    }

    override suspend fun revokeAll(
        accountId: AccountId,
        now: Instant,
    ) {
        database
            .sql("UPDATE account_session SET revoked_at = :now WHERE account_id = :accountId AND revoked_at IS NULL")
            .bind("now", now)
            .bind("accountId", accountId.value)
            .fetch()
            .awaitRowsUpdated()
    }

    private suspend fun rememberRefreshToken(session: SessionRecord) {
        database
            .sql("INSERT INTO session_refresh_token (token_hash, session_id) VALUES (:hash, :sessionId)")
            .bind("hash", session.refreshTokenHash.value)
            .bind("sessionId", session.id.value)
            .fetch()
            .awaitRowsUpdated()
    }

    private companion object {
        const val COLUMNS = "id, account_id, refresh_token_hash, issued_at, expires_at, rotated_at, revoked_at"

        fun sessionOf(row: Readable): SessionRecord =
            SessionRecord(
                SessionId(row.required<UUID>("id")),
                AccountId(row.required<UUID>("account_id")),
                TokenHash(row.required("refresh_token_hash")),
                row.required("issued_at"),
                row.required("expires_at"),
                row.optional("rotated_at"),
                row.optional("revoked_at"),
            )
    }
}

/** Outbound adapter: the `sign_in_source` table, keyed by the SHA-256 of the source address (no address is stored). */
class R2dbcSourceThrottleRepository(
    private val database: DatabaseClient,
    private val clock: Clock,
) : SourceThrottleRepository {
    override suspend fun find(source: String): SignInThrottle =
        database
            .sql("SELECT failures, locked_until FROM sign_in_source WHERE source_hash = :hash")
            .bind("hash", hashOf(source))
            .map { row -> SignInThrottle(row.required("failures"), row.optional("locked_until")) }
            .awaitOneOrNull() ?: SignInThrottle.CLEAR

    override suspend fun save(
        source: String,
        throttle: SignInThrottle,
    ) {
        database
            .sql(
                "INSERT INTO sign_in_source (source_hash, failures, locked_until, updated_at) " +
                    "VALUES (:hash, :failures, :lockedUntil, :now) ON CONFLICT (source_hash) DO UPDATE SET " +
                    "failures = EXCLUDED.failures, locked_until = EXCLUDED.locked_until, " +
                    "updated_at = EXCLUDED.updated_at",
            ).bind("hash", hashOf(source))
            .bind("failures", throttle.failures)
            .bindNullable("lockedUntil", throttle.lockedUntil, Instant::class.java)
            .bind("now", clock.instant())
            .fetch()
            .awaitRowsUpdated()
    }

    private companion object {
        fun hashOf(source: String): String = Digests.sha256Hex("sign-in-source:$source")
    }
}

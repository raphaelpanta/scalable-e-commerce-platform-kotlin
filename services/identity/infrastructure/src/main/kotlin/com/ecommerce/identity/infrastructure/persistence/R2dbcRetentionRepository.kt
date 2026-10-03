package com.ecommerce.identity.infrastructure.persistence

import com.ecommerce.identity.application.RetentionRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant

/**
 * Outbound adapter: deletes the identity rows whose retention ended (data-model section 5), one statement per table.
 * The refresh-token hashes of a deleted session go with it (`ON DELETE CASCADE`). Indexed by V3__retention_indexes.sql.
 */
class R2dbcRetentionRepository(
    private val database: DatabaseClient,
) : RetentionRepository {
    override suspend fun deleteTokensExpiredBefore(cutoff: Instant): Long =
        delete("DELETE FROM one_time_token WHERE expires_at < :cutoff", cutoff)

    override suspend fun deleteSessionsEndedBefore(cutoff: Instant): Long =
        delete("DELETE FROM account_session WHERE expires_at < :cutoff OR revoked_at < :cutoff", cutoff)

    override suspend fun deleteThrottlesIdleSince(
        cutoff: Instant,
        now: Instant,
    ): Long =
        database
            .sql(
                "DELETE FROM sign_in_source WHERE updated_at < :cutoff " +
                    "AND (locked_until IS NULL OR locked_until <= :now)",
            ).bind("cutoff", cutoff)
            .bind("now", now)
            .fetch()
            .awaitRowsUpdated()

    override suspend fun deletePhoneVerificationsExpiredBefore(cutoff: Instant): Long =
        delete("DELETE FROM phone_verification WHERE expires_at < :cutoff", cutoff)

    private suspend fun delete(
        statement: String,
        cutoff: Instant,
    ): Long =
        database
            .sql(statement)
            .bind("cutoff", cutoff)
            .fetch()
            .awaitRowsUpdated()
}

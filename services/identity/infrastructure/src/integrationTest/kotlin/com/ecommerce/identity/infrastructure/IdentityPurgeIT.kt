package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.Digests
import com.ecommerce.identity.infrastructure.jobs.IdentityPurgeJob
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * T128 (data-model section 5, FR-007): tokens are purged 7 days after expiry, sessions 30 days after expiry or
 * revocation (with their refresh-token hashes), idle unlocked sign-in throttles after a day and phone verifications a
 * day after expiry; everything younger, and every locked throttle, is kept.
 */
class IdentityPurgeIT(
    @Autowired private val purgeJob: IdentityPurgeJob,
) : IdentityIntegrationTest() {
    private fun daysAgo(days: Long): Instant = Instant.now().minus(Duration.ofDays(days))

    private fun execute(
        sql: String,
        bindings: Map<String, Any?>,
    ) {
        bindings.entries
            .filter { it.value != null }
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, checkNotNull(value)) }
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    private fun count(
        table: String,
        column: String,
        value: Any,
    ): Long =
        checkNotNull(
            database
                .sql("SELECT count(*) AS n FROM $table WHERE $column = :value")
                .bind("value", value)
                .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)) }
                .one()
                .block(QUERY_TIMEOUT),
        )

    private fun account(): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO account (id, email, status, roles, created_at, version) " +
                "VALUES (:id, :email, 'active', 'shopper', :now, 0)",
            mapOf("id" to id, "email" to freshEmail(), "now" to Instant.now()),
        )
        return id
    }

    private fun token(
        accountId: UUID,
        expiredDaysAgo: Long,
    ): String {
        val hash = Digests.sha256Hex(UUID.randomUUID().toString())
        execute(
            "INSERT INTO one_time_token (token_hash, account_id, purpose, issued_at, expires_at) " +
                "VALUES (:hash, :accountId, 'password_reset', :issued, :expires)",
            mapOf(
                "hash" to hash,
                "accountId" to accountId,
                "issued" to daysAgo(expiredDaysAgo + 1),
                "expires" to daysAgo(expiredDaysAgo),
            ),
        )
        return hash
    }

    private fun session(
        accountId: UUID,
        expiredDaysAgo: Long,
        revokedDaysAgo: Long? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val hash = Digests.sha256Hex(id.toString())
        execute(
            "INSERT INTO account_session (id, account_id, refresh_token_hash, issued_at, expires_at, revoked_at) " +
                "VALUES (:id, :accountId, :hash, :issued, :expires, " +
                (if (revokedDaysAgo == null) "NULL" else ":revoked") + ")",
            mapOf(
                "id" to id,
                "accountId" to accountId,
                "hash" to hash,
                "issued" to daysAgo(expiredDaysAgo + SESSION_DAYS),
                "expires" to daysAgo(expiredDaysAgo),
                "revoked" to revokedDaysAgo?.let(::daysAgo),
            ),
        )
        execute(
            "INSERT INTO session_refresh_token (token_hash, session_id) VALUES (:hash, :id)",
            mapOf("hash" to hash, "id" to id),
        )
        return id
    }

    private fun throttle(
        idleDays: Long,
        lockedForMinutes: Long? = null,
    ): String {
        val hash = Digests.sha256Hex("sign-in-source:" + freshSource())
        execute(
            "INSERT INTO sign_in_source (source_hash, failures, locked_until, updated_at) " +
                "VALUES (:hash, 5, " + (if (lockedForMinutes == null) "NULL" else ":locked") + ", :updated)",
            mapOf(
                "hash" to hash,
                "updated" to daysAgo(idleDays),
                "locked" to lockedForMinutes?.let { Instant.now().plus(Duration.ofMinutes(it)) },
            ),
        )
        return hash
    }

    private fun phoneVerification(
        accountId: UUID,
        expiredDaysAgo: Long,
    ) {
        execute(
            "INSERT INTO phone_verification (account_id, phone_number, code_hash, issued_at, expires_at, attempts) " +
                "VALUES (:accountId, '+5511900000000', :hash, :issued, :expires, 0)",
            mapOf(
                "accountId" to accountId,
                "hash" to Digests.sha256Hex(accountId.toString()),
                "issued" to daysAgo(expiredDaysAgo),
                "expires" to daysAgo(expiredDaysAgo),
            ),
        )
    }

    @Test
    fun `records past their retention are deleted and younger ones kept`() {
        val owner = account()
        val oldToken = token(owner, expiredDaysAgo = 8)
        val recentToken = token(owner, expiredDaysAgo = 6)
        val expiredSession = session(owner, expiredDaysAgo = 31)
        val revokedSession = session(owner, expiredDaysAgo = -1, revokedDaysAgo = 31)
        val recentlyRevoked = session(owner, expiredDaysAgo = -1, revokedDaysAgo = 29)
        val liveSession = session(owner, expiredDaysAgo = -10)
        val idleThrottle = throttle(idleDays = 2)
        val lockedThrottle = throttle(idleDays = 2, lockedForMinutes = 10)
        val busyThrottle = throttle(idleDays = 0)
        val stale = account()
        phoneVerification(stale, expiredDaysAgo = 2)
        val fresh = account()
        phoneVerification(fresh, expiredDaysAgo = 0)

        val report = runBlocking { purgeJob.purgeNow() }

        report.tokens shouldBeGreaterThanOrEqual 1L
        report.sessions shouldBeGreaterThanOrEqual 2L
        report.throttles shouldBeGreaterThanOrEqual 1L
        report.phoneVerifications shouldBeGreaterThanOrEqual 1L
        count("one_time_token", "token_hash", oldToken) shouldBe 0L
        count("one_time_token", "token_hash", recentToken) shouldBe 1L
        count("account_session", "id", expiredSession) shouldBe 0L
        count("account_session", "id", revokedSession) shouldBe 0L
        count("session_refresh_token", "session_id", revokedSession) shouldBe 0L
        count("account_session", "id", recentlyRevoked) shouldBe 1L
        count("account_session", "id", liveSession) shouldBe 1L
        count("sign_in_source", "source_hash", idleThrottle) shouldBe 0L
        count("sign_in_source", "source_hash", lockedThrottle) shouldBe 1L
        count("sign_in_source", "source_hash", busyThrottle) shouldBe 1L
        count("phone_verification", "account_id", stale) shouldBe 0L
        count("phone_verification", "account_id", fresh) shouldBe 1L
        count("account", "id", owner) shouldBe 1L
        purgeJob.isRunning shouldBe true
    }

    private companion object {
        const val SESSION_DAYS = 30L
    }
}

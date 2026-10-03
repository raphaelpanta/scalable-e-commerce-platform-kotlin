package com.ecommerce.identity.application

import java.time.Duration

/**
 * How long identity keeps records once they stopped being useful (data-model section 5, FR-007): tokens 7 days after
 * expiry, sessions 30 days after expiry or revocation, throttle counters once idle (and unlocked) for a day, phone
 * verifications a day after expiry (a late confirmation still answers "expired" meanwhile).
 */
data class RetentionPolicy(
    val tokenRetention: Duration = Duration.ofDays(DEFAULT_TOKEN_DAYS),
    val sessionRetention: Duration = Duration.ofDays(DEFAULT_SESSION_DAYS),
    val throttleIdle: Duration = Duration.ofDays(1),
    val phoneVerificationRetention: Duration = Duration.ofDays(1),
) {
    init {
        listOf(tokenRetention, sessionRetention, throttleIdle, phoneVerificationRetention).forEach {
            require(!it.isNegative) { "a retention cannot be negative" }
        }
    }

    private companion object {
        const val DEFAULT_TOKEN_DAYS = 7L
        const val DEFAULT_SESSION_DAYS = 30L
    }
}

/** What one purge deleted, per kind of record. */
data class PurgeReport(
    val tokens: Long,
    val sessions: Long,
    val throttles: Long,
    val phoneVerifications: Long,
) {
    val total: Long get() = tokens + sessions + throttles + phoneVerifications
}

/** Deletes every identity record whose retention under [policy] ended at the current time (data-model section 5). */
class PurgeRetainedData(
    private val retention: RetentionRepository,
    private val clock: Clock,
    private val policy: RetentionPolicy = RetentionPolicy(),
) {
    suspend operator fun invoke(): PurgeReport {
        val now = clock.now()
        return PurgeReport(
            tokens = retention.deleteTokensExpiredBefore(now.minus(policy.tokenRetention)),
            sessions = retention.deleteSessionsEndedBefore(now.minus(policy.sessionRetention)),
            throttles = retention.deleteThrottlesIdleSince(now.minus(policy.throttleIdle), now),
            phoneVerifications =
                retention.deletePhoneVerificationsExpiredBefore(now.minus(policy.phoneVerificationRetention)),
        )
    }
}

package com.ecommerce.identity.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant

private const val VERIFICATION_HOURS = 24L

/** What a one-time token proves, and how long it lives (data-model section 1: 24 h and 1 h). */
enum class TokenPurpose(
    val code: String,
    val ttl: Duration,
) {
    /** `VerificationToken`: proves ownership of the registered email address. */
    EMAIL_VERIFICATION("email_verification", Duration.ofHours(VERIFICATION_HOURS)),

    /** `PasswordResetToken`: allows one password change. */
    PASSWORD_RESET("password_reset", Duration.ofHours(1)),
    ;

    companion object {
        fun fromCode(code: String): TokenPurpose? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A verification or password-reset token (data-model section 3.1 `VerificationToken`, `PasswordResetToken`): only
 * its [hash] is stored; it is single use ([usedAt]) and valid until [expiresAt]. Issuing a new token of the same
 * purpose invalidates the older ones (the repository's job).
 */
data class OneTimeToken(
    val accountId: AccountId,
    val purpose: TokenPurpose,
    val hash: TokenHash,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
) {
    /** Unused and not expired at [now]. */
    fun isUsable(now: Instant): Boolean = usedAt == null && now.isBefore(expiresAt)

    /** Spends the token at [now], or [IdentityError.InvalidToken] when it is used or expired. */
    fun redeem(now: Instant): Either<IdentityError, OneTimeToken> =
        if (isUsable(now)) copy(usedAt = now).right() else IdentityError.InvalidToken.left()

    companion object {
        fun issue(
            accountId: AccountId,
            purpose: TokenPurpose,
            hash: TokenHash,
            now: Instant,
        ): OneTimeToken = OneTimeToken(accountId, purpose, hash, now, now.plus(purpose.ttl), null)
    }
}

/**
 * One signed-in session (data-model section 3.1 `SessionRecord`): it holds the hash of the current refresh token,
 * which rotates on every refresh, and lives until [expiresAt] (30 days) unless revoked (sign-out, password reset,
 * deletion, or reuse of a spent refresh token).
 */
data class SessionRecord(
    val id: SessionId,
    val accountId: AccountId,
    val refreshTokenHash: TokenHash,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rotatedAt: Instant?,
    val revokedAt: Instant?,
) {
    fun isActive(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)

    /**
     * Exchanges the [presented] refresh token for [next]: refused when the session is revoked or expired, and
     * reported as reuse when [presented] is not the current token (a spent one, the session must then be revoked).
     */
    fun rotate(
        presented: TokenHash,
        next: TokenHash,
        now: Instant,
    ): Either<IdentityError, SessionRecord> =
        when {
            !isActive(now) -> IdentityError.InvalidRefreshToken.left()
            !Digests.sameHash(presented, refreshTokenHash) -> IdentityError.RefreshTokenReused.left()
            else -> copy(refreshTokenHash = next, rotatedAt = now).right()
        }

    /** Revoked at [now]; an already revoked session keeps its first revocation time. */
    fun revoke(now: Instant): SessionRecord = if (revokedAt == null) copy(revokedAt = now) else this

    companion object {
        val DEFAULT_LIFETIME: Duration = Duration.ofDays(30)

        fun start(
            id: SessionId,
            accountId: AccountId,
            refreshTokenHash: TokenHash,
            now: Instant,
            lifetime: Duration = DEFAULT_LIFETIME,
        ): SessionRecord = SessionRecord(id, accountId, refreshTokenHash, now, now.plus(lifetime), null, null)
    }
}

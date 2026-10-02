package com.ecommerce.identity.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant

/**
 * The sign-in throttling rule (data-model section 1, FR-006): after [maxFailures] consecutive failures the subject
 * (an account or a source address) is locked for [lockDuration]; a success resets the count.
 */
data class ThrottlePolicy(
    val maxFailures: Int = DEFAULT_MAX_FAILURES,
    val lockDuration: Duration = DEFAULT_LOCK,
) {
    init {
        require(maxFailures >= 1) { "at least one failure before a lock" }
        require(lockDuration > Duration.ZERO) { "a lock lasts a positive time" }
    }

    companion object {
        const val DEFAULT_MAX_FAILURES: Int = 5
        val DEFAULT_LOCK: Duration = Duration.ofMinutes(15)
    }
}

/** Consecutive failed sign-ins of one subject and the end of its lock, if one was set. Orthogonal to the status. */
data class SignInThrottle(
    val failures: Int,
    val lockedUntil: Instant?,
) {
    init {
        require(failures >= 0) { "failures cannot be negative" }
    }

    /** True while the lock set by the last failure is still running at [now]. */
    fun isLocked(now: Instant): Boolean = lockedUntil != null && now.isBefore(lockedUntil)

    /** How long the lock still runs at [now] (zero when unlocked). */
    fun retryAfter(now: Instant): Duration =
        if (lockedUntil != null && now.isBefore(lockedUntil)) Duration.between(now, lockedUntil) else Duration.ZERO

    /** One more failure at [now]: the count restarts after an expired lock, and the [policy]'s limit locks. */
    fun afterFailure(
        now: Instant,
        policy: ThrottlePolicy,
    ): SignInThrottle {
        val expired = lockedUntil != null && !now.isBefore(lockedUntil)
        val failures = (if (expired) 0 else this.failures) + 1
        return if (failures >= policy.maxFailures) {
            SignInThrottle(failures, now.plus(policy.lockDuration))
        } else {
            SignInThrottle(failures, null)
        }
    }

    companion object {
        /** No failure recorded. */
        val CLEAR: SignInThrottle = SignInThrottle(0, null)
    }
}

/**
 * The account aggregate (data-model section 3.1). Every change returns a copy whose [version] is one higher (the
 * optimistic-locking token). Status `unverified` -> `active` by email verification; `deleted` (anonymised) is
 * terminal. The account keeps its id, roles and [pseudonym] after deletion; personal data is gone.
 */
data class Account(
    val id: AccountId,
    val email: Email,
    val passwordHash: PasswordHash?,
    val status: AccountStatus,
    val roles: Set<Role>,
    val displayName: DisplayName?,
    val createdAt: Instant,
    val verifiedAt: Instant?,
    val throttle: SignInThrottle,
    val deletedAt: Instant?,
    val pseudonym: Pseudonym?,
    val version: Long,
) {
    init {
        require(roles.isNotEmpty()) { "an account holds at least one role" }
    }

    val isDeleted: Boolean get() = status == AccountStatus.DELETED

    /** True once the email address was verified (the profile's `emailVerified`). */
    val emailVerified: Boolean get() = verifiedAt != null

    fun has(role: Role): Boolean = role in roles

    /** Verifies the email: an unverified account becomes active; an active one is unchanged. */
    fun verify(now: Instant): Either<IdentityError, Account> =
        when (status) {
            AccountStatus.UNVERIFIED -> next().copy(status = AccountStatus.ACTIVE, verifiedAt = now).right()
            AccountStatus.ACTIVE -> this.right()
            AccountStatus.DELETED -> IdentityError.InvalidToken.left()
        }

    /** A new password: the old one stops working and the failure count is reset (FR-006). */
    fun changePassword(hash: PasswordHash): Account = next().copy(passwordHash = hash, throttle = SignInThrottle.CLEAR)

    fun rename(displayName: DisplayName?): Account = next().copy(displayName = displayName)

    /** A failed sign-in at [now] under [policy]. */
    fun failedSignIn(
        now: Instant,
        policy: ThrottlePolicy,
    ): Account = next().copy(throttle = throttle.afterFailure(now, policy))

    /** A successful sign-in resets the failure count; unchanged when there was nothing to reset. */
    fun succeededSignIn(): Account =
        if (throttle ==
            SignInThrottle.CLEAR
        ) {
            this
        } else {
            next().copy(throttle = SignInThrottle.CLEAR)
        }

    /**
     * Deletes the account by anonymisation (FR-007): the email becomes the unusable placeholder of [pseudonym], the
     * password hash, display name and lock are cleared, the status is `deleted`; id and roles are kept. Operators
     * cannot delete themselves; a deleted account cannot be deleted again.
     */
    fun anonymise(
        pseudonym: Pseudonym,
        now: Instant,
    ): Either<IdentityError, Account> =
        when {
            isDeleted -> IdentityError.AccountNotFound.left()
            has(Role.OPERATOR) -> IdentityError.OperatorCannotSelfDelete.left()
            else -> anonymised(pseudonym, now).right()
        }

    private fun anonymised(
        pseudonym: Pseudonym,
        now: Instant,
    ): Account {
        val placeholder = Email.of(pseudonym.placeholderEmail).fold({ error(it.reason) }, { it })
        return next().copy(
            email = placeholder,
            passwordHash = null,
            status = AccountStatus.DELETED,
            displayName = null,
            throttle = SignInThrottle.CLEAR,
            deletedAt = now,
            pseudonym = pseudonym,
        )
    }

    private fun next(): Account = copy(version = version + 1)

    companion object {
        /** A newly registered shopper (FR-004): unverified, role `shopper` only (operators are seeded, FR-005). */
        fun register(
            id: AccountId,
            email: Email,
            passwordHash: PasswordHash,
            displayName: DisplayName?,
            now: Instant,
        ): Account =
            Account(
                id = id,
                email = email,
                passwordHash = passwordHash,
                status = AccountStatus.UNVERIFIED,
                roles = setOf(Role.SHOPPER),
                displayName = displayName,
                createdAt = now,
                verifiedAt = null,
                throttle = SignInThrottle.CLEAR,
                deletedAt = null,
                pseudonym = null,
                version = 0,
            )
    }
}

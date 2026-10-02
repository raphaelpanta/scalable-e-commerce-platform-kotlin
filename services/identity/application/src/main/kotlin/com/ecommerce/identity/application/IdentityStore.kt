package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.RecipientSnapshot
import com.ecommerce.identity.domain.ThrottlePolicy
import java.time.Duration

/** Tunable rules of the identity context (data-model section 1, research section 9). */
data class IdentityPolicies(
    /** Per-account sign-in throttle: 5 consecutive failures lock for 15 minutes. */
    val accountThrottle: ThrottlePolicy = ThrottlePolicy(),
    /** Per-source-address sign-in throttle (several people may share one address, so it is configurable). */
    val sourceThrottle: ThrottlePolicy = ThrottlePolicy(),
    /** Lifetime of access tokens (15 minutes). */
    val accessTokenLifetime: Duration = Duration.ofMinutes(DEFAULT_ACCESS_MINUTES),
    /** Lifetime of a session and its rotating refresh tokens (30 days). */
    val sessionLifetime: Duration = Duration.ofDays(DEFAULT_SESSION_DAYS),
) {
    private companion object {
        const val DEFAULT_ACCESS_MINUTES = 15L
        const val DEFAULT_SESSION_DAYS = 30L
    }
}

/** Attempts of an account change that lost an optimistic-locking race before it gives up. */
internal const val CONFLICT_ATTEMPTS: Int = 3

/** Every port the use cases need, plus the [policies]; shared by all use cases. */
data class IdentityStore(
    val accounts: AccountRepository,
    val addresses: AddressRepository,
    val preferences: PreferenceRepository,
    val tokens: TokenRepository,
    val sessions: SessionRepository,
    val sourceThrottles: SourceThrottleRepository,
    val hasher: PasswordHasher,
    val signer: TokenSigner,
    val secrets: Secrets,
    val sms: SmsSenderPort,
    val events: IdentityEvents,
    val transactions: Transactions,
    val clock: Clock,
    val policies: IdentityPolicies = IdentityPolicies(),
) {
    /** The live account [id], or [IdentityError.AccountNotFound] when it is unknown or deleted. */
    suspend fun liveAccount(id: AccountId): Either<IdentityError, Account> =
        accounts.findById(id)?.takeUnless { it.isDeleted }?.right() ?: IdentityError.AccountNotFound.left()

    /** The preferences of [accountId], email only when none were stored yet. */
    suspend fun preferenceOf(accountId: AccountId): NotificationPreference =
        preferences.find(accountId) ?: NotificationPreference.default(accountId)

    suspend fun recipientOf(account: Account): RecipientSnapshot =
        RecipientSnapshot.of(account, preferenceOf(account.id))

    /** Stores [changed] over [original], or [IdentityError.ConcurrentUpdate] when the account moved on. */
    suspend fun update(
        original: Account,
        changed: Account,
    ): Either<IdentityError, Account> =
        if (accounts.update(changed, original.version)) changed.right() else IdentityError.ConcurrentUpdate.left()

    /** Re-reads account [id] and stores [change] of it until it sticks, at most [CONFLICT_ATTEMPTS] times. */
    suspend fun changeAccount(
        id: AccountId,
        change: (Account) -> Account,
    ) {
        var attempt = 0
        var stored = false
        while (!stored && attempt < CONFLICT_ATTEMPTS) {
            attempt++
            val account = accounts.findById(id) ?: return
            val changed = change(account)
            stored = changed === account || accounts.update(changed, account.version)
        }
    }
}

package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.raise.either
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountContact
import com.ecommerce.identity.domain.AccountDeleted
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.DisplayName
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.Pseudonym

/** `getOwnProfile`: the caller's own live account. */
class GetProfile(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(accountId: AccountId): Either<IdentityError, Account> = store.liveAccount(accountId)
}

/** `updateOwnProfile`: changes the display name (email and roles cannot change here). */
class UpdateProfile(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        displayName: String?,
    ): Either<IdentityError, Account> =
        either {
            val name = DisplayName.of(displayName).mapLeft { IdentityError.Invalid(listOf(it)) }.bind()
            val account = store.liveAccount(accountId).bind()
            store.update(account, account.rename(name)).bind()
        }
}

/**
 * `deleteOwnAccount` (FR-007): anonymises the account (placeholder email, no password, no profile), removes every
 * address, the phone number and pending codes, revokes every session and token, and publishes `AccountDeleted` with
 * the pseudonym, in one transaction. Operators cannot delete themselves.
 */
class DeleteAccount(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(accountId: AccountId): Either<IdentityError, Unit> =
        store.transactions.inTransaction {
            either {
                val now = store.clock.now()
                val account = store.liveAccount(accountId).bind()
                val pseudonym = Pseudonym.of(account.id)
                store.update(account, account.anonymise(pseudonym, now).bind()).bind()
                store.addresses.save(store.addresses.book(account.id).cleared())
                store.preferences.save(NotificationPreference.default(account.id))
                store.preferences.deleteVerification(account.id)
                store.sessions.revokeAll(account.id, now)
                store.tokens.invalidateAll(account.id, now)
                store.events.accountDeleted(AccountDeleted(account.id, pseudonym, now))
            }
        }
}

/** `getAccountContact` (internal): contact details and permitted channels; anonymised accounts are still known. */
class GetAccountContact(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(accountId: AccountId): Either<IdentityError, AccountContact> =
        either {
            val account = store.accounts.findById(accountId) ?: raise(IdentityError.AccountNotFound)
            AccountContact.of(account, store.preferenceOf(accountId))
        }
}

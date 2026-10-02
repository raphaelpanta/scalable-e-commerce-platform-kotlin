package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountRegistered
import com.ecommerce.identity.domain.AccountVerified
import com.ecommerce.identity.domain.DisplayName
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.OneTimeToken
import com.ecommerce.identity.domain.OpaqueToken
import com.ecommerce.identity.domain.Password
import com.ecommerce.identity.domain.RecipientSnapshot
import com.ecommerce.identity.domain.TokenPurpose

/** A registration request as received (validated by [RegisterAccount]). Holds a password: `toString()` hides it. */
class Registration(
    val email: String,
    val password: String,
    val displayName: String?,
) {
    override fun toString(): String = "Registration(****)"
}

private class ValidRegistration(
    val email: Email,
    val password: Password,
    val displayName: DisplayName?,
)

/**
 * `registerAccount` (FR-004): creates an unverified shopper, its default preferences and a 24-hour verification
 * token, and publishes `AccountRegistered` with the token, in one transaction. An email that is already registered
 * creates nothing and gets the same answer, so callers cannot tell the cases apart; the password is hashed in both
 * cases so that the timing does not tell either.
 */
class RegisterAccount(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(registration: Registration): Either<IdentityError, Unit> =
        either {
            val email = Email.of(registration.email)
            val valid =
                Either
                    .zipOrAccumulate(
                        email,
                        Password.of(registration.password, email.getOrNull()),
                        DisplayName.of(registration.displayName),
                        ::ValidRegistration,
                    ).mapLeft { IdentityError.Invalid(it) }
                    .bind()
            val hash = store.hasher.hash(valid.password)
            if (store.accounts.findByEmail(valid.email) == null) {
                val id = AccountId(store.secrets.newId())
                val account = Account.register(id, valid.email, hash, valid.displayName, store.clock.now())
                store.transactions.inTransaction { create(account) }.bind()
            }
        }

    private suspend fun create(account: Account): Either<IdentityError, Unit> {
        if (!store.accounts.insert(account)) return Unit.right()
        val preference = NotificationPreference.default(account.id)
        store.preferences.save(preference)
        val token = store.secrets.opaqueToken()
        val issued = OneTimeToken.issue(account.id, TokenPurpose.EMAIL_VERIFICATION, token.hash(), account.createdAt)
        store.tokens.issue(issued)
        store.events.accountRegistered(
            AccountRegistered(RecipientSnapshot.of(account, preference), token, issued.expiresAt),
        )
        return Unit.right()
    }
}

/**
 * `verifyEmail` (FR-004): spends the single-use verification token and activates the account; the first
 * verification publishes `AccountVerified`. Unknown, used and expired tokens all answer [IdentityError.InvalidToken].
 */
class VerifyEmail(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(rawToken: String): Either<IdentityError, Unit> =
        either {
            val token = OpaqueToken.of(rawToken).mapLeft { IdentityError.InvalidToken }.bind()
            store.transactions.inTransaction { verify(token) }.bind()
        }

    private suspend fun verify(token: OpaqueToken): Either<IdentityError, Unit> =
        either {
            val now = store.clock.now()
            val stored =
                ensureNotNull(
                    store.tokens.find(TokenPurpose.EMAIL_VERIFICATION, token.hash()),
                ) { IdentityError.InvalidToken }
            val used = stored.redeem(now).bind()
            ensure(store.tokens.markUsed(used)) { IdentityError.InvalidToken }
            val account = ensureNotNull(store.accounts.findById(stored.accountId)) { IdentityError.InvalidToken }
            val verified = account.verify(now).bind()
            if (verified !== account) {
                store.update(account, verified).bind()
                store.events.accountVerified(AccountVerified(store.recipientOf(verified), now))
            }
        }
}

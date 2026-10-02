package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.OneTimeToken
import com.ecommerce.identity.domain.OpaqueToken
import com.ecommerce.identity.domain.Password
import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.domain.PasswordResetRequested
import com.ecommerce.identity.domain.TokenPurpose

/**
 * `requestPasswordReset` (FR-006): for a live account, issues a one-hour reset token (older ones stop working) and
 * publishes `PasswordResetRequested` with it. Unknown and malformed addresses get the same answer and nothing
 * happens, so the existence of an account is never revealed.
 */
class RequestPasswordReset(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(rawEmail: String): Either<IdentityError, Unit> {
        val account = Email.of(rawEmail).getOrNull()?.let { store.accounts.findByEmail(it) }
        return if (account == null) Unit.right() else store.transactions.inTransaction { issue(account) }
    }

    private suspend fun issue(account: Account): Either<IdentityError, Unit> {
        val token = store.secrets.opaqueToken()
        val issued = OneTimeToken.issue(account.id, TokenPurpose.PASSWORD_RESET, token.hash(), store.clock.now())
        store.tokens.issue(issued)
        store.events.passwordResetRequested(PasswordResetRequested(store.recipientOf(account), token, issued.expiresAt))
        return Unit.right()
    }
}

/** A password reset completion as received. Holds a token and a password: `toString()` hides both. */
class ResetCompletion(
    val token: String,
    val newPassword: String,
) {
    override fun toString(): String = "ResetCompletion(****)"
}

/**
 * `completePasswordReset` (FR-006): spends the single-use reset token and sets the new password, which must meet the
 * policy (checked before the token is spent); the old password stops working, the failure count is reset and every
 * session of the account is revoked.
 */
class CompletePasswordReset(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(completion: ResetCompletion): Either<IdentityError, Unit> =
        either {
            val token = OpaqueToken.of(completion.token).mapLeft { IdentityError.InvalidToken }.bind()
            val stored =
                ensureNotNull(
                    store.tokens.find(TokenPurpose.PASSWORD_RESET, token.hash()),
                ) { IdentityError.InvalidToken }
            ensure(stored.isUsable(store.clock.now())) { IdentityError.InvalidToken }
            val account = store.liveAccount(stored.accountId).mapLeft { IdentityError.InvalidToken }.bind()
            val password =
                Password
                    .of(
                        completion.newPassword,
                        account.email,
                        "newPassword",
                    ).mapLeft { IdentityError.Invalid(listOf(it)) }
                    .bind()
            val hash = store.hasher.hash(password)
            store.transactions.inTransaction { reset(stored, account, hash) }.bind()
        }

    private suspend fun reset(
        stored: OneTimeToken,
        account: Account,
        hash: PasswordHash,
    ): Either<IdentityError, Unit> =
        either {
            val now = store.clock.now()
            val used = stored.redeem(now).bind()
            ensure(store.tokens.markUsed(used)) { IdentityError.InvalidToken }
            store.update(account, account.changePassword(hash)).bind()
            store.sessions.revokeAll(account.id, now)
        }
}

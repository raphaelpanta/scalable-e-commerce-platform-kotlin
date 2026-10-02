package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.OpaqueToken
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.domain.SessionRecord
import com.ecommerce.identity.domain.SignInThrottle
import java.time.Instant

/** A sign-in request as received. Holds a password: `toString()` hides it. */
class Credentials(
    val email: String,
    val password: String,
    /** The caller's source address (throttled like the account). */
    val source: String,
) {
    override fun toString(): String = "Credentials(****)"
}

/** The tokens of a session: the access token and the opaque refresh token (credentials, never logged). */
class TokenPair(
    val accessToken: SignedAccessToken,
    val refreshToken: OpaqueToken,
) {
    override fun toString(): String = "TokenPair(****)"
}

/** Signs an access token for [account] in [session], or raises [IdentityError.SigningUnavailable]. */
private suspend fun Raise<IdentityError>.accessToken(
    store: IdentityStore,
    account: Account,
    sessionId: SessionId,
    now: Instant,
): SignedAccessToken =
    ensureNotNull(
        store.signer.sign(AccessGrant(account.id, account.roles, sessionId, now, store.policies.accessTokenLifetime)),
    ) { IdentityError.SigningUnavailable }

/**
 * `signIn` (FR-004, FR-006): email and password for a token pair. Unknown email and wrong password answer the same
 * [IdentityError.InvalidCredentials] and count as a failure of the account (when it exists) and of the source
 * address; the fifth consecutive failure locks for 15 minutes, during which every attempt (even with the right
 * password) answers [IdentityError.Throttled]. A success resets both counts; an unverified email is refused with
 * [IdentityError.EmailNotVerified].
 */
class SignIn(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(credentials: Credentials): Either<IdentityError, TokenPair> =
        either {
            val now = store.clock.now()
            val source = store.sourceThrottles.find(credentials.source)
            ensure(!source.isLocked(now)) { IdentityError.Throttled(source.retryAfter(now)) }
            val account = Email.of(credentials.email).getOrNull()?.let { store.accounts.findByEmail(it) }
            if (account != null) {
                ensure(!account.throttle.isLocked(now)) { IdentityError.Throttled(account.throttle.retryAfter(now)) }
            }
            val matches = store.hasher.verify(credentials.password, account?.passwordHash)
            ensure(matches && account != null) {
                failed(account?.id, credentials.source, source, now)
                IdentityError.InvalidCredentials
            }
            succeeded(account, credentials.source, source)
            ensure(account.status == AccountStatus.ACTIVE) { IdentityError.EmailNotVerified }
            startSession(account, now)
        }

    private suspend fun Raise<IdentityError>.startSession(
        account: Account,
        now: Instant,
    ): TokenPair {
        val refresh = store.secrets.opaqueToken()
        val session =
            SessionRecord.start(
                SessionId(store.secrets.newId()),
                account.id,
                refresh.hash(),
                now,
                store.policies.sessionLifetime,
            )
        val access = accessToken(store, account, session.id, now)
        store.sessions.insert(session)
        return TokenPair(access, refresh)
    }

    private suspend fun failed(
        accountId: AccountId?,
        source: String,
        throttle: SignInThrottle,
        now: Instant,
    ) {
        store.sourceThrottles.save(source, throttle.afterFailure(now, store.policies.sourceThrottle))
        if (accountId != null) store.changeAccount(accountId) { it.failedSignIn(now, store.policies.accountThrottle) }
    }

    private suspend fun succeeded(
        account: Account,
        source: String,
        throttle: SignInThrottle,
    ) {
        if (throttle != SignInThrottle.CLEAR) store.sourceThrottles.save(source, SignInThrottle.CLEAR)
        store.changeAccount(account.id) { it.succeededSignIn() }
    }
}

/**
 * `refreshSession`: exchanges a refresh token for a new pair; the refresh token rotates. A spent refresh token
 * presented again revokes its session (token theft); revoked, expired and unknown tokens, and sessions of accounts
 * that are no longer active, answer [IdentityError.InvalidRefreshToken].
 */
class RefreshSession(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(rawToken: String): Either<IdentityError, TokenPair> =
        either {
            val now = store.clock.now()
            val presented = OpaqueToken.of(rawToken).mapLeft { IdentityError.InvalidRefreshToken }.bind()
            val session =
                ensureNotNull(store.sessions.findByRefreshHash(presented.hash())) { IdentityError.InvalidRefreshToken }
            val next = store.secrets.opaqueToken()
            val rotated =
                session
                    .rotate(presented.hash(), next.hash(), now)
                    .mapLeft { error ->
                        if (error == IdentityError.RefreshTokenReused) store.sessions.revoke(session.id, now)
                        IdentityError.InvalidRefreshToken
                    }.bind()
            val account = store.accounts.findById(session.accountId)
            ensure(account != null && account.status == AccountStatus.ACTIVE) {
                IdentityError.InvalidRefreshToken
            }
            val access = accessToken(store, account, session.id, now)
            ensure(store.sessions.rotate(rotated, presented.hash())) { IdentityError.InvalidRefreshToken }
            TokenPair(access, next)
        }
}

/** `signOut`: revokes the caller's session (the `sid` of the access token) and with it its refresh token. */
class SignOut(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        sessionId: SessionId?,
    ) {
        val session = sessionId?.let { store.sessions.find(it) }
        if (session != null && session.accountId == accountId) store.sessions.revoke(session.id, store.clock.now())
    }
}

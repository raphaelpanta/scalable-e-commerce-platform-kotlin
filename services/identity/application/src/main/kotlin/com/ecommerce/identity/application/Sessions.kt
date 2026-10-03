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
 * [IdentityError.InvalidCredentials] and count as a failure of the source address and of the account, or, for an
 * email without a live account, of that email (so that unknown addresses lock exactly like accounts and the 429 tells
 * nothing). The fifth consecutive failure locks for 15 minutes, during which every attempt (even with the right
 * password) answers [IdentityError.Throttled]. Locks are checked before the slow password hash, and failures are
 * counted under a row lock so that parallel attempts are all counted. A success resets both counts; an unverified
 * email is refused with [IdentityError.EmailNotVerified].
 */
class SignIn(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(credentials: Credentials): Either<IdentityError, TokenPair> =
        either {
            val now = store.clock.now()
            val source = ThrottleKey.source(credentials.source)
            val sourceThrottle = store.throttles.find(source)
            ensureUnlocked(sourceThrottle, now)
            val account = Email.of(credentials.email).getOrNull()?.let { store.accounts.findByEmail(it) }
            val unknownEmail = ThrottleKey.email(credentials.email)
            ensureUnlocked(account?.throttle ?: store.throttles.find(unknownEmail), now)
            val matches = store.hasher.verify(credentials.password, account?.passwordHash)
            ensure(matches && account != null) {
                failed(account, unknownEmail, source, now)
                IdentityError.InvalidCredentials
            }
            succeeded(account, source, sourceThrottle)
            ensure(account.status == AccountStatus.ACTIVE) { IdentityError.EmailNotVerified }
            startSession(account, now)
        }

    private fun Raise<IdentityError>.ensureUnlocked(
        throttle: SignInThrottle,
        now: Instant,
    ) = ensure(!throttle.isLocked(now)) { IdentityError.Throttled(throttle.retryAfter(now)) }

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
        account: Account?,
        unknownEmail: ThrottleKey,
        source: ThrottleKey,
        now: Instant,
    ) {
        store.throttles.change(source) { it.afterFailure(now, store.policies.sourceThrottle) }
        if (account == null) {
            store.throttles.change(unknownEmail) { it.afterFailure(now, store.policies.accountThrottle) }
        } else {
            store.accounts.changeLocked(account.id) { it.failedSignIn(now, store.policies.accountThrottle) }
        }
    }

    private suspend fun succeeded(
        account: Account,
        source: ThrottleKey,
        sourceThrottle: SignInThrottle,
    ) {
        if (sourceThrottle != SignInThrottle.CLEAR) store.throttles.change(source) { SignInThrottle.CLEAR }
        if (account.throttle != SignInThrottle.CLEAR) store.accounts.changeLocked(account.id) { it.succeededSignIn() }
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

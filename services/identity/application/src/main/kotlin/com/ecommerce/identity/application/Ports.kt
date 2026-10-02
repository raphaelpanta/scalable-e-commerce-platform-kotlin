package com.ecommerce.identity.application

import arrow.core.Either
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountDeleted
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountRegistered
import com.ecommerce.identity.domain.AccountVerified
import com.ecommerce.identity.domain.AddressBook
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.OneTimeToken
import com.ecommerce.identity.domain.OpaqueToken
import com.ecommerce.identity.domain.Password
import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.domain.PasswordResetRequested
import com.ecommerce.identity.domain.PhoneNumber
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.domain.SessionRecord
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.TokenHash
import com.ecommerce.identity.domain.TokenPurpose
import com.ecommerce.identity.domain.VerificationCode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Outbound port: the accounts, with optimistic locking on [Account.version]. */
interface AccountRepository {
    suspend fun findById(id: AccountId): Account?

    /** The live (not deleted) account registered with [email]. */
    suspend fun findByEmail(email: Email): Account?

    /** Stores a new account; false when a live account already uses its email. */
    suspend fun insert(account: Account): Boolean

    /** Replaces a stored account still at [expectedVersion]; false when it moved on. */
    suspend fun update(
        account: Account,
        expectedVersion: Long,
    ): Boolean
}

/** Outbound port: the delivery addresses of each account. */
interface AddressRepository {
    /** Every address of [accountId], in creation order (an empty book when it has none). */
    suspend fun book(accountId: AccountId): AddressBook

    /** Stores [book] as the complete set of addresses of its account. */
    suspend fun save(book: AddressBook)
}

/** Outbound port: notification preferences and pending phone verifications. */
interface PreferenceRepository {
    /** The stored preferences of [accountId], or null when it never had any. */
    suspend fun find(accountId: AccountId): NotificationPreference?

    suspend fun save(preference: NotificationPreference)

    suspend fun pendingVerification(accountId: AccountId): PhoneVerification?

    /** Stores [verification] as the account's only pending verification. */
    suspend fun saveVerification(verification: PhoneVerification)

    suspend fun deleteVerification(accountId: AccountId)
}

/** Outbound port: verification and password-reset tokens, stored by hash only. */
interface TokenRepository {
    /** Stores [token] and invalidates every older unused token of the same account and purpose. */
    suspend fun issue(token: OneTimeToken)

    suspend fun find(
        purpose: TokenPurpose,
        hash: TokenHash,
    ): OneTimeToken?

    /** Marks [used] as used if it still is unused; false when another request spent it first. */
    suspend fun markUsed(used: OneTimeToken): Boolean

    /** Invalidates every unused token of [accountId] (deletion). */
    suspend fun invalidateAll(
        accountId: AccountId,
        now: Instant,
    )
}

/** Outbound port: sessions and the hashes of every refresh token they handed out. */
interface SessionRepository {
    suspend fun insert(session: SessionRecord)

    suspend fun find(id: SessionId): SessionRecord?

    /** The session that handed out the refresh token with [hash], current or already spent. */
    suspend fun findByRefreshHash(hash: TokenHash): SessionRecord?

    /** Stores [rotated] if its current refresh token is still [previous]; false when it moved on. */
    suspend fun rotate(
        rotated: SessionRecord,
        previous: TokenHash,
    ): Boolean

    suspend fun revoke(
        id: SessionId,
        now: Instant,
    )

    suspend fun revokeAll(
        accountId: AccountId,
        now: Instant,
    )
}

/** Outbound port: the sign-in throttle of source addresses (only a hash of the address is stored). */
interface SourceThrottleRepository {
    suspend fun find(source: String): SignInThrottle

    suspend fun save(
        source: String,
        throttle: SignInThrottle,
    )
}

/** Outbound port: the slow adaptive password hash (Argon2id). */
interface PasswordHasher {
    suspend fun hash(password: Password): PasswordHash

    /** True when [raw] matches [hash]; a null [hash] costs the same work and is false (no account enumeration). */
    suspend fun verify(
        raw: String,
        hash: PasswordHash?,
    ): Boolean
}

/** What an access token states: the account, its roles and its session, issued at [issuedAt] for [lifetime]. */
data class AccessGrant(
    val accountId: AccountId,
    val roles: Set<Role>,
    val sessionId: SessionId,
    val issuedAt: Instant,
    val lifetime: Duration,
)

/** A signed access token and its lifetime. The token is a credential: `toString()` never reveals it. */
class SignedAccessToken(
    val value: String,
    val expiresIn: Duration,
) {
    override fun toString(): String = "SignedAccessToken(expiresIn=$expiresIn)"
}

/** Outbound port: signs access tokens (EdDSA JWT); null while no signing key is available. */
fun interface TokenSigner {
    suspend fun sign(grant: AccessGrant): SignedAccessToken?
}

/** Outbound port: the current time (UTC, at the precision the database keeps). */
fun interface Clock {
    fun now(): Instant
}

/** Outbound port: fresh randomness (opaque tokens, phone codes, ids). */
interface Secrets {
    fun opaqueToken(): OpaqueToken

    fun verificationCode(): VerificationCode

    fun newId(): UUID
}

/** Outbound port: sends one SMS; false when the channel did not accept it. */
fun interface SmsSenderPort {
    suspend fun send(
        to: PhoneNumber,
        text: String,
    ): Boolean
}

/** Outbound port: the account events (events.yaml `identity.account.v1`), written in the caller's transaction. */
interface IdentityEvents {
    suspend fun accountRegistered(event: AccountRegistered)

    suspend fun accountVerified(event: AccountVerified)

    suspend fun passwordResetRequested(event: PasswordResetRequested)

    suspend fun accountDeleted(event: AccountDeleted)
}

/** Outbound port: runs a block in one database transaction, rolled back when the block returns an error. */
interface Transactions {
    suspend fun <T> inTransaction(block: suspend () -> Either<IdentityError, T>): Either<IdentityError, T>
}

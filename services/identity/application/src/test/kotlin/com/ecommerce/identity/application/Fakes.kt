package com.ecommerce.identity.application

import arrow.core.Either
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountDeleted
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountRegistered
import com.ecommerce.identity.domain.AccountVerified
import com.ecommerce.identity.domain.AddressBook
import com.ecommerce.identity.domain.DisplayName
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
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.domain.SessionRecord
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.TokenHash
import com.ecommerce.identity.domain.TokenPurpose
import com.ecommerce.identity.domain.VerificationCode
import io.kotest.assertions.fail
import kotlinx.coroutines.yield
import java.time.Duration
import java.time.Instant
import java.util.UUID

val NOW: Instant = Instant.parse("2026-10-02T10:00:00Z")
const val PASSWORD = "S3cure-passphrase!"
const val ADA = "ada@example.test"

fun <E, T> Either<E, T>.value(): T = fold({ fail("expected a value but was $it") }, { it })

fun <E, T> Either<E, T>.error(): E = fold({ it }, { fail("expected an error but was $it") })

fun email(raw: String = ADA): Email = Email.of(raw).value()

fun phone(raw: String = "+5511987654321"): PhoneNumber = PhoneNumber.of(raw).value()

/** Opaque tokens `aaaa...`, `bbbb...` in order; codes `100000`, `100001` in order. */
class FakeSecrets : Secrets {
    var tokens = 0
    var codes = 0
    var ids = 0

    override fun opaqueToken(): OpaqueToken =
        OpaqueToken.of(('a' + tokens++).toString().repeat(OpaqueToken.LENGTH)).value()

    override fun verificationCode(): VerificationCode = VerificationCode.of((CODE_BASE + codes++).toString()).value()

    override fun newId(): UUID = UUID(0L, (++ids).toLong())

    companion object {
        const val CODE_BASE = 100_000

        fun token(index: Int): OpaqueToken = OpaqueToken.of(('a' + index).toString().repeat(OpaqueToken.LENGTH)).value()
    }
}

/** A clock that tests move forward by hand. */
class FakeClock(
    var instant: Instant = NOW,
) : Clock {
    override fun now(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }
}

/** "Hashes" by prefixing; counts calls so tests can tell that a missing account still costs a verification. */
class FakeHasher : PasswordHasher {
    var hashed = 0
    var verified = 0

    override suspend fun hash(password: Password): PasswordHash {
        yield()
        hashed++
        return PasswordHash("hash:" + password.value)
    }

    override suspend fun verify(
        raw: String,
        hash: PasswordHash?,
    ): Boolean {
        yield()
        verified++
        return hash != null && hash.value == "hash:$raw"
    }
}

class FakeSigner : TokenSigner {
    var available = true
    val grants = mutableListOf<AccessGrant>()

    override suspend fun sign(grant: AccessGrant): SignedAccessToken? {
        yield()
        if (!available) return null
        grants += grant
        return SignedAccessToken("jwt-${grants.size}", grant.lifetime)
    }
}

class FakeSms : SmsSenderPort {
    var accepting = true
    val sent = mutableListOf<Pair<PhoneNumber, String>>()

    override suspend fun send(
        to: PhoneNumber,
        text: String,
    ): Boolean {
        yield()
        if (accepting) sent += to to text
        return accepting
    }
}

class InMemoryAccounts : AccountRepository {
    val accounts = linkedMapOf<AccountId, Account>()
    var losingUpdates = 0
    var updates = 0

    fun store(vararg stored: Account) = stored.forEach { accounts[it.id] = it }

    operator fun get(id: AccountId): Account = accounts[id] ?: fail("no account $id")

    override suspend fun findById(id: AccountId): Account? {
        yield()
        return accounts[id]
    }

    override suspend fun findByEmail(email: Email): Account? {
        yield()
        return accounts.values.firstOrNull { it.email == email && !it.isDeleted }
    }

    override suspend fun insert(account: Account): Boolean {
        yield()
        if (accounts.values.any { it.email == account.email && !it.isDeleted }) return false
        accounts[account.id] = account
        return true
    }

    override suspend fun update(
        account: Account,
        expectedVersion: Long,
    ): Boolean {
        yield()
        updates++
        if (losingUpdates > 0 || accounts[account.id]?.version != expectedVersion) {
            losingUpdates--
            return false
        }
        accounts[account.id] = account
        return true
    }

    var lockedChanges = 0

    /** Atomic like the row lock of the adapter: nothing suspends between the read and the write. */
    override suspend fun changeLocked(
        id: AccountId,
        change: (Account) -> Account,
    ): Account? {
        yield()
        val account = accounts[id] ?: return null
        lockedChanges++
        return change(account).also { accounts[id] = it }
    }
}

class InMemoryAddresses : AddressRepository {
    val books = linkedMapOf<AccountId, AddressBook>()

    override suspend fun book(accountId: AccountId): AddressBook {
        yield()
        return books[accountId] ?: AddressBook.empty(accountId)
    }

    override suspend fun save(book: AddressBook) {
        yield()
        books[book.accountId] = book
    }
}

class InMemoryPreferences : PreferenceRepository {
    val preferences = linkedMapOf<AccountId, NotificationPreference>()
    val verifications = linkedMapOf<AccountId, PhoneVerification>()

    override suspend fun find(accountId: AccountId): NotificationPreference? {
        yield()
        return preferences[accountId]
    }

    override suspend fun save(preference: NotificationPreference) {
        yield()
        preferences[preference.accountId] = preference
    }

    override suspend fun pendingVerification(accountId: AccountId): PhoneVerification? {
        yield()
        return verifications[accountId]
    }

    override suspend fun saveVerification(verification: PhoneVerification) {
        yield()
        verifications[verification.accountId] = verification
    }

    override suspend fun deleteVerification(accountId: AccountId) {
        yield()
        verifications.remove(accountId)
    }
}

class InMemoryTokens : TokenRepository {
    val tokens = linkedMapOf<TokenHash, OneTimeToken>()
    var losingMarks = 0

    override suspend fun issue(token: OneTimeToken) {
        yield()
        tokens.replaceAll { _, old ->
            if (old.accountId == token.accountId && old.purpose == token.purpose && old.usedAt == null) {
                old.copy(usedAt = token.issuedAt)
            } else {
                old
            }
        }
        tokens[token.hash] = token
    }

    override suspend fun find(
        purpose: TokenPurpose,
        hash: TokenHash,
    ): OneTimeToken? {
        yield()
        return tokens[hash]?.takeIf { it.purpose == purpose }
    }

    override suspend fun markUsed(used: OneTimeToken): Boolean {
        yield()
        if (losingMarks > 0 || tokens[used.hash]?.usedAt != null) {
            losingMarks--
            return false
        }
        tokens[used.hash] = used
        return true
    }

    override suspend fun invalidateAll(
        accountId: AccountId,
        now: Instant,
    ) {
        yield()
        tokens.replaceAll { _, old ->
            if (old.accountId == accountId &&
                old.usedAt == null
            ) {
                old.copy(usedAt = now)
            } else {
                old
            }
        }
    }

    fun of(
        accountId: AccountId,
        purpose: TokenPurpose,
    ): List<OneTimeToken> = tokens.values.filter { it.accountId == accountId && it.purpose == purpose }
}

class InMemorySessions : SessionRepository {
    val sessions = linkedMapOf<SessionId, SessionRecord>()
    val spent = linkedMapOf<TokenHash, SessionId>()
    var losingRotations = 0

    override suspend fun insert(session: SessionRecord) {
        yield()
        sessions[session.id] = session
    }

    override suspend fun find(id: SessionId): SessionRecord? {
        yield()
        return sessions[id]
    }

    override suspend fun findByRefreshHash(hash: TokenHash): SessionRecord? {
        yield()
        return sessions.values.firstOrNull { it.refreshTokenHash == hash } ?: spent[hash]?.let { sessions[it] }
    }

    override suspend fun rotate(
        rotated: SessionRecord,
        previous: TokenHash,
    ): Boolean {
        yield()
        if (losingRotations > 0 || sessions[rotated.id]?.refreshTokenHash != previous) {
            losingRotations--
            return false
        }
        spent[previous] = rotated.id
        sessions[rotated.id] = rotated
        return true
    }

    override suspend fun revoke(
        id: SessionId,
        now: Instant,
    ) {
        yield()
        sessions[id]?.let { sessions[id] = it.revoke(now) }
    }

    override suspend fun revokeAll(
        accountId: AccountId,
        now: Instant,
    ) {
        yield()
        sessions.replaceAll { _, session -> if (session.accountId == accountId) session.revoke(now) else session }
    }

    fun of(accountId: AccountId): List<SessionRecord> = sessions.values.filter { it.accountId == accountId }
}

class InMemoryThrottles : ThrottleRepository {
    val throttles = linkedMapOf<ThrottleKey, SignInThrottle>()
    var changes = 0

    operator fun get(key: ThrottleKey): SignInThrottle = throttles[key] ?: SignInThrottle.CLEAR

    override suspend fun find(key: ThrottleKey): SignInThrottle {
        yield()
        return get(key)
    }

    /** Atomic like the row lock of the adapter: nothing suspends between the read and the write. */
    override suspend fun change(
        key: ThrottleKey,
        change: (SignInThrottle) -> SignInThrottle,
    ): SignInThrottle {
        yield()
        changes++
        return change(get(key)).also { throttles[key] = it }
    }
}

class RecordedEvents : IdentityEvents {
    val registered = mutableListOf<AccountRegistered>()
    val verified = mutableListOf<AccountVerified>()
    val resets = mutableListOf<PasswordResetRequested>()
    val deleted = mutableListOf<AccountDeleted>()

    val all: List<Any> get() = registered + verified + resets + deleted

    override suspend fun accountRegistered(event: AccountRegistered) {
        yield()
        registered += event
    }

    override suspend fun accountVerified(event: AccountVerified) {
        yield()
        verified += event
    }

    override suspend fun passwordResetRequested(event: PasswordResetRequested) {
        yield()
        resets += event
    }

    override suspend fun accountDeleted(event: AccountDeleted) {
        yield()
        deleted += event
    }
}

/** Runs the block and restores every in-memory store when it returns an error, like a rolled-back transaction. */
class FakeTransactions(
    private val harness: Harness,
) : Transactions {
    var transactions = 0
    var rollbacks = 0

    override suspend fun <T> inTransaction(block: suspend () -> Either<IdentityError, T>): Either<IdentityError, T> {
        yield()
        transactions++
        val snapshot = harness.snapshot()
        return block().onLeft {
            rollbacks++
            harness.restore(snapshot)
        }
    }
}

/** Every port in memory at a fixed clock. */
class Harness(
    policies: IdentityPolicies = IdentityPolicies(),
) {
    val accounts = InMemoryAccounts()
    val addresses = InMemoryAddresses()
    val preferences = InMemoryPreferences()
    val tokens = InMemoryTokens()
    val sessions = InMemorySessions()
    val throttles = InMemoryThrottles()
    val hasher = FakeHasher()
    val signer = FakeSigner()
    val secrets = FakeSecrets()
    val sms = FakeSms()
    val events = RecordedEvents()
    val transactions = FakeTransactions(this)
    val clock = FakeClock()
    val store =
        IdentityStore(
            accounts,
            addresses,
            preferences,
            tokens,
            sessions,
            throttles,
            hasher,
            signer,
            secrets,
            sms,
            events,
            transactions,
            clock,
            policies,
        )

    /** A stored, verified shopper with [PASSWORD]. */
    fun shopper(
        address: String = ADA,
        id: AccountId = AccountId(UUID.randomUUID()),
    ): Account {
        val account =
            Account
                .register(id, email(address), PasswordHash("hash:$PASSWORD"), DisplayName.of("Ada").value(), NOW)
                .verify(NOW)
                .value()
        accounts.store(account)
        return account
    }

    internal fun snapshot(): List<Map<*, *>> =
        listOf(
            LinkedHashMap(accounts.accounts),
            LinkedHashMap(addresses.books),
            LinkedHashMap(preferences.preferences),
            LinkedHashMap(preferences.verifications),
            LinkedHashMap(tokens.tokens),
            LinkedHashMap(sessions.sessions),
        )

    @Suppress("UNCHECKED_CAST")
    internal fun restore(snapshot: List<Map<*, *>>) {
        accounts.accounts.restoreFrom(snapshot[0] as Map<AccountId, Account>)
        addresses.books.restoreFrom(snapshot[1] as Map<AccountId, AddressBook>)
        preferences.preferences.restoreFrom(snapshot[2] as Map<AccountId, NotificationPreference>)
        preferences.verifications.restoreFrom(snapshot[3] as Map<AccountId, PhoneVerification>)
        tokens.tokens.restoreFrom(snapshot[4] as Map<TokenHash, OneTimeToken>)
        sessions.sessions.restoreFrom(snapshot[5] as Map<SessionId, SessionRecord>)
    }

    private fun <K, V> MutableMap<K, V>.restoreFrom(source: Map<K, V>) {
        clear()
        putAll(source)
    }
}

package com.ecommerce.identity.infrastructure

import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.provider.IHttpClientFactory
import au.com.dius.pact.provider.IProviderInfo
import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.identity.application.AccessGrant
import com.ecommerce.identity.application.Clock
import com.ecommerce.identity.application.TokenSigner
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountDeleted
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountRegistered
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.AccountVerified
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.OneTimeToken
import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.domain.PasswordResetRequested
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.Pseudonym
import com.ecommerce.identity.domain.RecipientSnapshot
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.domain.SessionRecord
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.TokenPurpose
import com.ecommerce.identity.infrastructure.messaging.IdentityEnvelopes
import com.ecommerce.identity.infrastructure.security.SecureSecrets
import com.ecommerce.identity.infrastructure.security.SigningKey
import com.ecommerce.identity.infrastructure.security.SigningKeyRing
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.PostgresTestConfig
import kotlinx.coroutines.runBlocking
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.HttpRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private val SEEDED_AT: Instant = Instant.parse("2026-10-02T09:00:00Z")
private const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
private val ACCESS_TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)
private val EXPIRED_FOR: Duration = Duration.ofHours(1)

/**
 * Provider side for every consumer of identity (pact-interactions.md sections 2.1, 2.5, 2.6, 3.2 and 5, and the
 * storefront rows I1 to I18 of specs/005-storefront-dev-bootstrap/contracts/pact-matrix.md), against the running
 * service. HTTP interactions (JWKS, internal address and contact lookups, health, the storefront's public API) run
 * against the server; message interactions verify the envelopes that [IdentityEnvelopes], the builder of the outbox
 * publisher, makes from domain objects. Provider states create exactly the data their parameters (or, for the
 * storefront, their sentences and [Storefront]) describe. The storefront's protected requests carry a placeholder
 * bearer: [SigningHttpTestTarget] replaces it with a token identity signs for the account of the current state, as
 * no consumer can forge the EdDSA signature. Shared by [IdentityProviderVerificationTest] (pacts of `build/pacts`)
 * and [IdentityBrokerVerificationTest] (pacts of the Pact Broker), which only choose the pact source; both are
 * tagged `provider` and run in `contractVerify`.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["management.server.port=", "platform.security.internal-token=" + InternalToken.TEST],
)
@Import(PostgresTestConfig::class)
// Abstract: JUnit runs only the subclasses, which choose the pact source (folder or broker); one state method per
// provider state and one producer per message description.
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
abstract class IdentityProviderStates {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var database: DatabaseClient

    @Autowired
    protected lateinit var ring: SigningKeyRing

    @Autowired
    protected lateinit var envelopes: IdentityEnvelopes

    @Autowired
    protected lateinit var signer: TokenSigner

    @Autowired
    protected lateinit var clock: Clock

    private val seeds: StorefrontSeeds by lazy { StorefrontSeeds(database) }

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        signedIn = null
        if (context != null) {
            context.target =
                if (context.interaction.isAsynchronousMessage()) {
                    MessageTestTarget(listOf(javaClass.packageName))
                } else {
                    SigningHttpTestTarget()
                }
        }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun identityHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * The HTTP target of the storefront's protected routes: when a state signed an account in, the consumer's
     * placeholder `Authorization` header is replaced by an access token identity signed for that account and session.
     * Requests without the header (`no valid token`) and interactions of other consumers are replayed unchanged. The
     * client never retries: Apache HttpClient would otherwise honour the `Retry-After` of a 429 and re-send the
     * throttled sign-in once the lock has lapsed.
     */
    private inner class SigningHttpTestTarget : HttpTestTarget("localhost", port, "/", { NoRetryHttpClientFactory }) {
        override fun prepareRequest(
            pact: Pact,
            interaction: Interaction,
            context: MutableMap<String, Any>,
        ): Pair<Any, Any>? {
            val prepared = super.prepareRequest(pact, interaction, context)
            prepared?.toList()?.filterIsInstance<HttpRequest>()?.forEach(::sign)
            return prepared
        }
    }

    private object NoRetryHttpClientFactory : IHttpClientFactory {
        override fun newClient(provider: IProviderInfo): CloseableHttpClient =
            HttpClients.custom().disableAutomaticRetries().build()
    }

    private fun sign(request: HttpRequest) {
        val grant = signedIn ?: return
        if (request.getFirstHeader(HttpHeaders.AUTHORIZATION) == null) return
        val token = checkNotNull(runBlocking { signer.sign(grant) }) { "identity has no signing key" }
        request.removeHeaders(HttpHeaders.AUTHORIZATION)
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ${token.value}")
    }

    // --- HTTP provider states ------------------------------------------------------------------------------------

    @State("the identity service is running")
    fun serviceRunning() {
        // The Spring context and its database are already up; nothing to arrange.
    }

    @State("identity has an active signing key")
    fun activeSigningKey(parameters: Map<String, Any?>) {
        ring.replace(listOf(SigningKey.generate(text(parameters, "kid"))))
    }

    @State("identity has rotated its signing key")
    fun rotatedSigningKey(parameters: Map<String, Any?>) {
        ring.replace(
            listOf(SigningKey.generate(text(parameters, "kid")), SigningKey.generate(text(parameters, "previousKid"))),
        )
    }

    @State("identity has no signing key available")
    fun noSigningKey() {
        ring.replace(emptyList())
    }

    @State("an address exists")
    fun addressExists(parameters: Map<String, Any?>) {
        val accountId = uuid(parameters, "accountId")
        val addressId = uuid(parameters, "addressId")
        ensureAccount(accountId)
        execute("DELETE FROM address WHERE id = :id", mapOf("id" to addressId))
        database
            .sql(
                "INSERT INTO address (id, account_id, position, label, recipient_name, line1, line2, city, region, " +
                    "postal_code, country_code, is_default) VALUES (:id, :accountId, 0, NULL, :recipientName, " +
                    ":line1, " +
                    ":line2, :city, :region, :postalCode, :countryCode, true)",
            ).bind("id", addressId)
            .bind("accountId", accountId)
            .bind("recipientName", text(parameters, "recipientName"))
            .bind("line1", text(parameters, "line1"))
            .bindOptional("line2", parameters["line2"] as String?)
            .bind("city", text(parameters, "city"))
            .bindOptional("region", parameters["region"] as String?)
            .bind("postalCode", text(parameters, "postalCode"))
            .bind("countryCode", text(parameters, "countryCode"))
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    @State("no address exists")
    fun noAddressExists(parameters: Map<String, Any?>) {
        ensureAccount(uuid(parameters, "accountId"))
        execute("DELETE FROM address WHERE id = :id", mapOf("id" to uuid(parameters, "addressId")))
    }

    @State("an account exists")
    fun accountExists(parameters: Map<String, Any?>) {
        val accountId = uuid(parameters, "accountId")
        seedAccount(accountId, text(parameters, "email"))
        val channels = (parameters["channels"] as? List<*>).orEmpty().joinToString(",")
        database
            .sql(
                "INSERT INTO notification_preference (account_id, channels, phone_number, phone_verified) " +
                    "VALUES (:id, :channels, :phone, :verified)",
            ).bind("id", accountId)
            .bind("channels", channels)
            .bindOptional("phone", parameters["phoneNumber"] as String?)
            .bind("verified", parameters["phoneVerified"] as? Boolean ?: false)
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    @State("an account is anonymised")
    fun accountIsAnonymised(parameters: Map<String, Any?>) {
        val accountId = uuid(parameters, "accountId")
        val pseudonym = Pseudonym(text(parameters, "pseudonym"))
        seedAccount(accountId, pseudonym.placeholderEmail)
        execute(
            "UPDATE account SET status = 'deleted', password_hash = NULL, display_name = NULL, deleted_at = :at, " +
                "pseudonym = :pseudonym WHERE id = :id",
            mapOf("id" to accountId, "at" to SEEDED_AT, "pseudonym" to pseudonym.value),
        )
    }

    @State("no account exists")
    fun noAccountExists(parameters: Map<String, Any?>) {
        execute("DELETE FROM account WHERE id = :id", mapOf("id" to uuid(parameters, "accountId")))
    }

    // --- Storefront provider states (pact-matrix.md "Storefront to identity", I1 to I18) ----------------------------

    @State(
        "an account ana@example.com exists with a verified email and password S3cure-passphrase!",
        "an account ana@example.com exists with a verified email",
    )
    fun anaVerified() {
        ana(AccountStatus.ACTIVE)
    }

    @State("an account ana@example.com exists with an unverified email")
    fun anaUnverified() {
        ana(AccountStatus.UNVERIFIED)
    }

    @State("ana@example.com has 5 failed sign-ins")
    fun anaThrottled() {
        ana(AccountStatus.ACTIVE, SignInThrottle(Storefront.FAILED_SIGN_INS, clock.now().plus(Storefront.RETRY_AFTER)))
    }

    @State("no account exists for new@example.com")
    fun noAccountForNewcomer() {
        seeds.deleteAccount(Storefront.NEW_EMAIL)
    }

    @State("a pending verification token tok-valid exists")
    fun pendingVerificationToken() {
        ana(AccountStatus.UNVERIFIED)
        val purpose = TokenPurpose.EMAIL_VERIFICATION
        seeds.oneTimeToken(Storefront.ANA_ID, purpose, Storefront.VERIFICATION_TOKEN, clock.now().plus(purpose.ttl))
    }

    @State("the verification token tok-expired is expired")
    fun expiredVerificationToken() {
        ana(AccountStatus.UNVERIFIED)
        val purpose = TokenPurpose.EMAIL_VERIFICATION
        seeds.oneTimeToken(Storefront.ANA_ID, purpose, Storefront.EXPIRED_TOKEN, clock.now().minus(EXPIRED_FOR))
    }

    @State("a pending password reset token tok-reset exists")
    fun pendingResetToken() {
        ana(AccountStatus.ACTIVE)
        val purpose = TokenPurpose.PASSWORD_RESET
        seeds.oneTimeToken(Storefront.ANA_ID, purpose, Storefront.RESET_TOKEN, clock.now().plus(purpose.ttl))
    }

    @State("an account ana@example.com has a valid refresh token")
    fun anaRefreshToken() {
        ana(AccountStatus.ACTIVE)
        session(Storefront.ANA_ID, Storefront.REFRESH_TOKEN)
    }

    @State("an account ana@example.com is signed in", "ana@example.com has no addresses")
    fun anaSignedIn() {
        ana(AccountStatus.ACTIVE)
        signIn(Storefront.ANA_ID, Role.SHOPPER)
    }

    @State("no valid token")
    fun noValidToken() {
        signedIn = null
    }

    @State("an operator ops@example.com is signed in")
    fun operatorSignedIn() {
        seeds.account(Storefront.OPS_ID, Storefront.OPS_EMAIL, AccountStatus.ACTIVE, Role.OPERATOR)
        signIn(Storefront.OPS_ID, Role.OPERATOR)
    }

    @State("ana@example.com has 2 addresses")
    fun anaAddresses() {
        anaSignedIn()
        seeds.addresses(Storefront.ANA_ID, Storefront.HOME_ADDRESS_ID to "Home", Storefront.WORK_ADDRESS_ID to "Work")
    }

    @State("ana@example.com has email notifications enabled")
    fun anaEmailNotifications() {
        anaSignedIn()
        seeds.preference(Storefront.ANA_ID, Storefront.EMAIL_CHANNEL)
    }

    @State("ana@example.com has a pending phone verification")
    fun anaPhoneVerification() {
        anaEmailNotifications()
        // Issued long enough ago that another code may be requested at once (I16 `requestPhoneVerification`).
        val issuedAt = clock.now().minus(PhoneVerification.RESEND_INTERVAL)
        seeds.phoneVerification(
            Storefront.ANA_ID,
            Storefront.PHONE,
            Storefront.PHONE_CODE,
            issuedAt,
            PhoneVerification.TTL,
        )
    }

    /** Ana as a shopper with her display name and password, [status] and [throttle]. */
    private fun ana(
        status: AccountStatus,
        throttle: SignInThrottle = SignInThrottle.CLEAR,
    ) {
        seeds.account(
            Storefront.ANA_ID,
            Storefront.ANA_EMAIL,
            status,
            displayName = Storefront.ANA_NAME,
            passwordHash = Storefront.PASSWORD_HASH,
            throttle = throttle,
        )
    }

    /** The session [Storefront.SESSION_ID] of [accountId] with the current [refreshToken]. */
    private fun session(
        accountId: UUID,
        refreshToken: String,
    ) {
        seeds.session(accountId, Storefront.SESSION_ID, refreshToken, clock.now(), SessionRecord.DEFAULT_LIFETIME)
    }

    /** Signs [accountId] in with [role]: a session to sign out of and the grant [sign] mints the bearer from. */
    private fun signIn(
        accountId: UUID,
        role: Role,
    ) {
        if (ring.active == null) ring.replace(listOf(SigningKey.generate()))
        session(accountId, SecureSecrets().opaqueToken().value)
        signedIn =
            AccessGrant(
                AccountId(accountId),
                setOf(role),
                SessionId(Storefront.SESSION_ID),
                clock.now(),
                ACCESS_TOKEN_LIFETIME,
            )
    }

    // --- Message provider states and producers ---------------------------------------------------------------------

    @State("an account was registered", "an account was verified", "a password reset was requested")
    fun accountEvent(parameters: Map<String, Any?>) {
        subject = uuid(parameters, "accountId") to text(parameters, "email")
    }

    @State("an account was deleted")
    fun accountWasDeleted(parameters: Map<String, Any?>) {
        deleted = uuid(parameters, "accountId") to Pseudonym(text(parameters, "pseudonym"))
    }

    @PactVerifyProvider("an AccountRegistered event for a new account")
    fun accountRegistered(): MessageAndMetadata {
        val token = SecureSecrets().opaqueToken()
        val issued =
            OneTimeToken.issue(
                subjectAccount().id,
                TokenPurpose.EMAIL_VERIFICATION,
                token.hash(),
                Instant.now(),
            )
        return message(
            envelopes.accountRegistered(AccountRegistered(recipient(), token, issued.expiresAt), CORRELATION_ID),
        )
    }

    @PactVerifyProvider("an AccountVerified event for a verified account")
    fun accountVerified(): MessageAndMetadata =
        message(envelopes.accountVerified(AccountVerified(recipient(), Instant.now()), CORRELATION_ID))

    @PactVerifyProvider("a PasswordResetRequested event for an account")
    fun passwordResetRequested(): MessageAndMetadata {
        val token = SecureSecrets().opaqueToken()
        val issued = OneTimeToken.issue(subjectAccount().id, TokenPurpose.PASSWORD_RESET, token.hash(), Instant.now())
        return message(
            envelopes.passwordResetRequested(
                PasswordResetRequested(recipient(), token, issued.expiresAt),
                CORRELATION_ID,
            ),
        )
    }

    @PactVerifyProvider("an AccountDeleted event for a deleted account")
    fun accountDeletedForNotification(): MessageAndMetadata = accountDeleted()

    @PactVerifyProvider("an AccountDeleted event for the owner of open orders")
    fun accountDeletedForOrder(): MessageAndMetadata = accountDeleted()

    private fun accountDeleted(): MessageAndMetadata {
        val (accountId, pseudonym) = checkNotNull(deleted) { "no state named the deleted account" }
        return message(
            envelopes.accountDeleted(AccountDeleted(AccountId(accountId), pseudonym, Instant.now()), CORRELATION_ID),
        )
    }

    /** The account of the message state, as registered: unverified, email only. */
    private fun subjectAccount(): Account {
        val (accountId, email) = checkNotNull(subject) { "no state named the account" }
        return Account.register(
            AccountId(accountId),
            Email.of(email).fold({ error(it.reason) }, { it }),
            PasswordHash("unused"),
            null,
            Instant.now(),
        )
    }

    private fun recipient(): RecipientSnapshot {
        val account = subjectAccount()
        return RecipientSnapshot.of(account, NotificationPreference.default(account.id))
    }

    private fun message(envelope: Envelope<*>): MessageAndMetadata =
        MessageAndMetadata(
            EnvelopeJson.write(envelope).toByteArray(Charsets.UTF_8),
            mapOf(
                "topic" to Topic.ACCOUNT,
                "kafkaKey" to envelope.aggregateId.toString(),
                "contentType" to "application/json",
            ),
        )

    // --- Seeding helpers -------------------------------------------------------------------------------------------

    /** Recreates account [id] as an active shopper with [email] (its addresses and preferences go with it). */
    private fun seedAccount(
        id: UUID,
        email: String,
    ) {
        execute("DELETE FROM account WHERE id = :id OR email = :email", mapOf("id" to id, "email" to email))
        execute(
            "INSERT INTO account (id, email, password_hash, status, roles, display_name, created_at, verified_at, " +
                "failed_sign_ins, locked_until, deleted_at, pseudonym, version) VALUES (:id, :email, NULL, 'active', " +
                "'shopper', NULL, :at, :at, 0, NULL, NULL, NULL, 0)",
            mapOf("id" to id, "email" to email, "at" to SEEDED_AT),
        )
    }

    /** Makes sure account [id] exists (an active shopper), keeping it when it does. */
    private fun ensureAccount(id: UUID) {
        execute(
            "INSERT INTO account (id, email, password_hash, status, roles, display_name, created_at, verified_at, " +
                "failed_sign_ins, locked_until, deleted_at, pseudonym, version) VALUES (:id, :email, NULL, 'active', " +
                "'shopper', NULL, :at, :at, 0, NULL, NULL, NULL, 0) ON CONFLICT DO NOTHING",
            mapOf("id" to id, "email" to "account-$id@example.test", "at" to SEEDED_AT),
        )
    }

    private fun execute(
        sql: String,
        bindings: Map<String, Any>,
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindOptional(
        name: String,
        value: String?,
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, String::class.java) else bind(name, value)

    companion object {
        /** The account of the last account message state (state methods and producers may run on other instances). */
        @Volatile
        private var subject: Pair<UUID, String>? = null

        @Volatile
        private var deleted: Pair<UUID, Pseudonym>? = null

        /** The account a storefront state signed in, whose bearer [sign] mints; null for anonymous requests. */
        @Volatile
        private var signedIn: AccessGrant? = null

        private fun text(
            parameters: Map<String, Any?>,
            name: String,
        ): String = checkNotNull(parameters[name]) { "provider state parameter $name" }.toString()

        private fun uuid(
            parameters: Map<String, Any?>,
            name: String,
        ): UUID = UUID.fromString(text(parameters, name))
    }
}

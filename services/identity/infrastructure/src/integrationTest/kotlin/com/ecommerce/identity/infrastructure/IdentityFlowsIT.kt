package com.ecommerce.identity.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.envelope.payloadAs
import com.ecommerce.platform.security.Ed25519Jwks
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val NEW_PASSWORD = "Another-passphrase-2026"
private const val ACCESS_SECONDS = 900L
private const val SOURCE_LIMIT = 5
private const val LOCK_AFTER = 5
private const val TOKEN_LENGTH = 43
private const val DEFAULT_PAGE_SIZE = 20
private val LOCK: Duration = Duration.ofMinutes(15)
private val CLOCK_SKEW: Duration = Duration.ofMinutes(1)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private val ARGON2ID =
    Regex("""^\${'$'}argon2id\${'$'}v=19\${'$'}m=19456,t=2,p=1\${'$'}[A-Za-z0-9+/]{22}\${'$'}[A-Za-z0-9+/]{43}$""")
private val CODE = Regex("""\b(\d{6})\b""")

/** T050: the identity flows end to end against PostgreSQL, Kafka (outbox relay) and Mailpit (SMS mirror). */
@Suppress("TooManyFunctions") // one test per flow of T050
class IdentityFlowsIT : IdentityIntegrationTest() {
    @Test
    fun `register, verify, sign in, refresh and sign out`() {
        val account = TestAccount(freshEmail())
        val accepted =
            post(
                "$IDENTITY/accounts",
                mapOf("email" to account.email, "password" to PASSWORD),
            ).json(ACCEPTED)
        accepted["message"] shouldBe "If the address can be registered, a verification message has been sent."

        val registered = registeredEvent(account.email)
        val payload = registered.payloadAs<Json>()
        account.id = payload["accountId"] as String
        registered.producer shouldBe "identity"
        registered.aggregateId.toString() shouldBe account.id
        recorded.awaitEvent(registered.eventId).key shouldBe account.id
        (payload["verificationToken"] as String).length shouldBe TOKEN_LENGTH
        accountColumn(account.email, "password_hash").shouldNotBeNull() shouldMatch ARGON2ID
        signIn(account.email, PASSWORD).expectProblem(ProblemType.FORBIDDEN)

        post(
            "$IDENTITY/accounts/verify-email",
            mapOf("token" to payload["verificationToken"]),
        ).expectStatus().isNoContent
        recorded.awaitType(EventType.AccountVerified) { it.aggregateId.toString() == account.id }
        post("$IDENTITY/accounts/verify-email", mapOf("token" to payload["verificationToken"]))
            .expectProblem(ProblemType.VALIDATION)

        val pair = signIn(account.email, PASSWORD).json(OK)
        pair["tokenType"] shouldBe "Bearer"
        (pair["expiresIn"] as Number).toLong() shouldBe ACCESS_SECONDS
        account.accessToken = pair["accessToken"] as String
        account.refreshToken = pair["refreshToken"] as String
        val profile = get(ME, account.bearer).json(OK)
        profile["id"] shouldBe account.id
        profile["emailVerified"] shouldBe true
        profile["roles"] shouldBe listOf("shopper")

        val refreshed = post("$IDENTITY/sessions/refresh", mapOf("refreshToken" to account.refreshToken)).json(OK)
        refreshed["refreshToken"] shouldNotBe account.refreshToken
        val rotated = refreshed["refreshToken"] as String
        delete("$IDENTITY/sessions/current", "Bearer ${refreshed["accessToken"]}").expectStatus().isNoContent
        post("$IDENTITY/sessions/refresh", mapOf("refreshToken" to rotated)).expectProblem(ProblemType.UNAUTHORIZED)
    }

    @Test
    fun `access tokens validate against the service's own JWKS and carry the shared claims`() {
        val account = signedIn()
        val jwks = JWKSet.parse(get("/.well-known/jwks.json").json(OK))
        jwks.keys.forEach { key ->
            key.toJSONObject().keys shouldBe setOf("kty", "crv", "kid", "x", "use", "alg")
            key.toJSONObject()["alg"] shouldBe "EdDSA"
        }

        val token = SignedJWT.parse(account.accessToken)
        val key = jwks.getKeyByKeyId(token.header.keyID).shouldNotBeNull() as OctetKeyPair
        Ed25519Jwks.verify(Ed25519Jwks.publicKeyOf(key), token.signingInput, token.signature.decode()) shouldBe true
        token.header.algorithm.name shouldBe "EdDSA"
        token.header.type.type shouldBe "JWT"
        val claims = token.jwtClaimsSet
        claims.subject shouldBe account.id
        claims.getStringListClaim("roles") shouldBe listOf("shopper")
        claims.issuer shouldBe "https://identity.ecommerce.local"
        claims.toJSONObject()["aud"] shouldBe "ecommerce-api"
        (claims.expirationTime.time - claims.issueTime.time) / MILLIS shouldBe ACCESS_SECONDS
        UUID.fromString(claims.jwtid).shouldNotBeNull()
        claims.issueTime.toInstant().epochSecond.shouldBeBetween(
            Instant.now().minus(CLOCK_SKEW).epochSecond,
            Instant.now().epochSecond,
        )
    }

    @Test
    fun `a registered email gets the same answer and no second verification message`() {
        val account = registerAndVerify()
        val first = registeredEvent(account.email).eventId

        val again = post("$IDENTITY/accounts", mapOf("email" to account.email.uppercase(), "password" to NEW_PASSWORD))
        again.json(ACCEPTED)["message"] shouldBe
            "If the address can be registered, a verification message has been sent."
        recorded.expectNone(Duration.ofSeconds(2)) {
            it.topic == Topic.ACCOUNT &&
                it.headers["type"] == "AccountRegistered" &&
                recipientEmail(it.envelope) == account.email &&
                it.envelope.eventId != first
        }
        signIn(account.email, NEW_PASSWORD).expectProblem(ProblemType.UNAUTHORIZED)
        post(
            "$IDENTITY/accounts",
            mapOf("email" to freshEmail(), "password" to "short"),
        ).expectProblem(ProblemType.VALIDATION)
        post("$IDENTITY/accounts", mapOf("email" to freshEmail())).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `registering again while unverified re-sends a token and takes the latest password (T134, T173)`() {
        val email = freshEmail()
        post("$IDENTITY/accounts", mapOf("email" to email, "password" to PASSWORD)).json(ACCEPTED)
        val first = registeredEvent(email)
        val firstHash = accountColumn(email, "password_hash").shouldNotBeNull()

        val again =
            post("$IDENTITY/accounts", mapOf("email" to email.uppercase(), "password" to NEW_PASSWORD)).json(ACCEPTED)

        again["message"] shouldBe "If the address can be registered, a verification message has been sent."
        val second =
            recorded.awaitType(EventType.AccountRegistered) {
                recipientEmail(it) == email && it.eventId != first.eventId
            }
        second.aggregateId shouldBe first.aggregateId
        val firstToken = first.payloadAs<Json>()["verificationToken"] as String
        val secondToken = second.payloadAs<Json>()["verificationToken"] as String
        secondToken shouldNotBe firstToken
        accountColumn(email, "password_hash").shouldNotBeNull() shouldMatch ARGON2ID
        accountColumn(email, "password_hash") shouldNotBe firstHash
        post("$IDENTITY/accounts/verify-email", mapOf("token" to firstToken)).expectProblem(ProblemType.VALIDATION)
        post("$IDENTITY/accounts/verify-email", mapOf("token" to secondToken)).expectStatus().isNoContent
        signIn(email, PASSWORD).expectProblem(ProblemType.UNAUTHORIZED)
        signIn(email, NEW_PASSWORD).expectStatus().isOk
    }

    @Test
    fun `five wrong passwords lock the account even for the right one, and a success resets the count`() {
        val locked = registerAndVerify()
        repeat(LOCK_AFTER) { signIn(locked.email, "Wrong-$it-password").expectProblem(ProblemType.UNAUTHORIZED) }

        val throttled = signIn(locked.email, PASSWORD)
        throttled.expectHeader().value(
            "Retry-After",
        ) { it.toLong().shouldBeBetween(1L, LOCK.seconds) }
        throttled.expectProblem(ProblemType.THROTTLED)

        val reset = registerAndVerify()
        repeat(LOCK_AFTER - 1) { signIn(reset.email, "Wrong-$it-password").expectProblem(ProblemType.UNAUTHORIZED) }
        signIn(reset.email, PASSWORD).expectStatus().isOk
        repeat(LOCK_AFTER - 1) { signIn(reset.email, "Wrong-$it-password").expectProblem(ProblemType.UNAUTHORIZED) }
        signIn(reset.email, PASSWORD).expectStatus().isOk
        accountColumn(reset.email, "failed_sign_ins") shouldBe "0"
    }

    @Test
    fun `a source address is throttled after its limit of consecutive failures`() {
        val source = freshSource()
        repeat(SOURCE_LIMIT) { signIn(freshEmail(), PASSWORD, source).expectProblem(ProblemType.UNAUTHORIZED) }
        val account = registerAndVerify()

        signIn(account.email, PASSWORD, source).expectProblem(ProblemType.THROTTLED)
        signIn(account.email, PASSWORD).expectStatus().isOk
    }

    @Test
    fun `a password reset works once, replaces the password and revokes the sessions`() {
        val account = signedIn()
        val unknown = post("$IDENTITY/password-resets", mapOf("email" to freshEmail())).json(ACCEPTED)
        val known = post("$IDENTITY/password-resets", mapOf("email" to account.email)).json(ACCEPTED)
        known shouldBe unknown

        val event = recorded.awaitType(EventType.PasswordResetRequested) { it.aggregateId.toString() == account.id }
        val token = event.payloadAs<Json>()["resetToken"] as String
        post("$IDENTITY/password-resets/complete", mapOf("token" to token, "newPassword" to "short"))
            .expectProblem(ProblemType.VALIDATION)
        post("$IDENTITY/password-resets/complete", mapOf("token" to token, "newPassword" to NEW_PASSWORD))
            .expectStatus()
            .isNoContent

        signIn(account.email, PASSWORD).expectProblem(ProblemType.UNAUTHORIZED)
        signIn(account.email, NEW_PASSWORD).expectStatus().isOk
        post(
            "$IDENTITY/sessions/refresh",
            mapOf("refreshToken" to account.refreshToken),
        ).expectProblem(ProblemType.UNAUTHORIZED)
        post("$IDENTITY/password-resets/complete", mapOf("token" to token, "newPassword" to "Yet-another-passphrase"))
            .expectProblem(ProblemType.VALIDATION)
    }

    @Test
    fun `addresses are added, listed, replaced and removed by their owner only`() {
        val ada = signedIn()
        val grace = signedIn()
        val lisboa = post("$ME/addresses", address("Lisboa"), ada.bearer).expectStatus().isCreated
        val lisboaId =
            lisboa
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
                ?.get("id") as String
        val porto = post("$ME/addresses", address("Porto"), ada.bearer).json(CREATED)
        porto["isDefault"] shouldBe true

        put("$ME/addresses/${porto["id"]}", address("Braga"), ada.bearer).json(OK)["city"] shouldBe "Braga"
        delete("$ME/addresses/$lisboaId", ada.bearer).expectStatus().isNoContent
        val page = get("$ME/addresses", ada.bearer).json(OK)
        (page["items"] as List<*>).map { (it as Map<*, *>)["city"] } shouldContainExactly listOf("Braga")
        page["totalItems"] shouldBe 1
        page["page"] shouldBe 0
        page["size"] shouldBe DEFAULT_PAGE_SIZE

        put("$ME/addresses/${porto["id"]}", address("Faro"), grace.bearer).expectProblem(ProblemType.NOT_FOUND)
        delete("$ME/addresses/${porto["id"]}", grace.bearer).expectProblem(ProblemType.NOT_FOUND)
        post(
            "$ME/addresses",
            address("Faro") + ("countryCode" to "XX"),
            ada.bearer,
        ).expectProblem(ProblemType.VALIDATION)
        get("$ME/addresses").expectProblem(ProblemType.UNAUTHORIZED)
        get("$ME/addresses?size=101", ada.bearer).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `a phone number is verified with the SMS code and enables the sms channel`() {
        val account = signedIn()
        val phone = "+55119" + (10_000_000..99_999_999).random()
        get("$ME/notification-preferences", account.bearer).json(OK) shouldBe
            mapOf("channels" to listOf("email"), "phoneVerified" to false)
        put("$ME/notification-preferences", mapOf("channels" to listOf("email", "sms")), account.bearer)
            .expectProblem(ProblemType.VALIDATION)

        post("$ME/phone-verifications", mapOf("phoneNumber" to phone), account.bearer).expectStatus().isAccepted
        val code =
            CODE
                .find(awaitSms(phone))
                ?.groupValues
                ?.get(1)
                .shouldNotBeNull()
        post(
            "$ME/phone-verifications/confirm",
            mapOf("code" to "x"),
            account.bearer,
        ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        post("$ME/phone-verifications/confirm", mapOf("code" to code), account.bearer).expectStatus().isNoContent
        put(
            "$ME/notification-preferences",
            mapOf("channels" to listOf("email", "sms")),
            account.bearer,
        ).json(OK) shouldBe
            mapOf("channels" to listOf("email", "sms"), "phoneNumber" to phone, "phoneVerified" to true)

        val contact = internal("/internal/accounts/${account.id}/contact").json(OK)
        contact["channels"] shouldBe listOf("email", "sms")
        contact["phoneNumber"] shouldBe phone
        contact["anonymised"] shouldBe false
    }

    @Test
    fun `deleting an account anonymises it and announces AccountDeleted`() {
        val account = signedIn()
        post("$ME/addresses", address("Lisboa"), account.bearer).expectStatus().isCreated

        delete(ME, account.bearer).expectStatus().isNoContent

        val deleted = recorded.awaitType(EventType.AccountDeleted) { it.aggregateId.toString() == account.id }
        val pseudonym = deleted.payloadAs<Json>()["pseudonym"] as String
        pseudonym shouldStartWith "anon-"
        accountColumn("$pseudonym@anonymised.invalid", "status") shouldBe "deleted"
        accountColumn("$pseudonym@anonymised.invalid", "password_hash") shouldBe null
        get(ME, account.bearer).expectProblem(ProblemType.UNAUTHORIZED)
        signIn(account.email, PASSWORD).expectProblem(ProblemType.UNAUTHORIZED)
        post(
            "$IDENTITY/sessions/refresh",
            mapOf("refreshToken" to account.refreshToken),
        ).expectProblem(ProblemType.UNAUTHORIZED)
        internal("/internal/accounts/${account.id}/contact").json(OK) shouldBe
            mapOf(
                "accountId" to account.id,
                "email" to "$pseudonym@anonymised.invalid",
                "phoneVerified" to false,
                "channels" to emptyList<String>(),
                "anonymised" to true,
            )
    }

    @Test
    fun `operators cannot delete themselves`() {
        val operator = registerAndVerify()
        database
            .sql("UPDATE account SET roles = 'shopper,operator' WHERE id = :id")
            .bind("id", UUID.fromString(operator.id))
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
        signedIn(operator)

        delete(ME, operator.bearer).expectProblem(ProblemType.FORBIDDEN)
    }

    @Test
    fun `internal endpoints need the internal token and hide other accounts' addresses`() {
        val ada = signedIn()
        val grace = signedIn()
        val home = post("$ME/addresses", address("Lisboa"), ada.bearer).json(CREATED)

        client
            .get()
            .uri("/internal/accounts/${ada.id}/contact")
            .exchange()
            .expectProblem(ProblemType.UNAUTHORIZED)
        internal("/internal/accounts/${ada.id}/addresses/${home["id"]}").json(OK) shouldBe
            mapOf(
                "recipientName" to "Acceptance Shopper",
                "line1" to "Rua das Flores 12",
                "city" to "Lisboa",
                "postalCode" to "1000-001",
                "countryCode" to "PT",
            )
        internal("/internal/accounts/${grace.id}/addresses/${home["id"]}").expectProblem(ProblemType.NOT_FOUND)
        internal("/internal/accounts/${UUID.randomUUID()}/contact").expectProblem(ProblemType.NOT_FOUND)
        internal("/internal/accounts/not-a-uuid/contact").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        internal("/internal/accounts/${ada.id}/contact").json(OK)["email"] shouldBe ada.email
    }

    private fun internal(path: String) =
        client
            .get()
            .uri(path)
            .header(InternalToken.HEADER, InternalToken.TEST)
            .exchange()

    private fun address(city: String): Map<String, Any> =
        mapOf(
            "label" to city,
            "recipientName" to "Acceptance Shopper",
            "line1" to "Rua das Flores 12",
            "city" to city,
            "postalCode" to "1000-001",
            "countryCode" to "PT",
            "isDefault" to true,
        )

    private companion object {
        const val MILLIS = 1000L
    }
}

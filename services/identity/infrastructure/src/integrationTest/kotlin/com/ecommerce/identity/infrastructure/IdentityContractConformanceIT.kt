package com.ecommerce.identity.infrastructure

import com.ecommerce.conformance.OpenApiContract
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.payloadAs
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID

private const val NO_CONTENT = 204
private const val NEW_PASSWORD = "Another-passphrase-2026"
private const val SOURCE_LIMIT = 5
private const val LONG_NAME = 101
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private val CODE = Regex("""\b(\d{6})\b""")

/**
 * Every public operation of `contracts/openapi/identity.yaml`, with its success and documented error statuses,
 * exercised against the running service and validated request by request (pact-matrix rule 3, constitution
 * Principle V). Statuses the service cannot produce on its own are deferred with the reason.
 */
@Suppress("TooManyFunctions") // one step per group of operations, plus the request helpers
class IdentityContractConformanceIT : IdentityIntegrationTest() {
    private val contract = OpenApiContract.of("identity")

    @Test
    fun `the public operations conform to identity yaml`() {
        val account = registration()
        sessions(account)
        profile(account)
        addresses(account)
        preferencesAndPhone(account)
        passwordReset(account)
        deletion()
        contract.verify(DEFERRED)
    }

    /** Registers and verifies an account through the contract; returns it signed in. */
    private fun registration(): TestAccount {
        val account = TestAccount(email())
        contract.check(post(ACCOUNTS, mapOf("email" to account.email, "password" to PASSWORD)), ACCEPTED)
        contract.check(post(ACCOUNTS, mapOf("email" to email())), BAD_REQUEST)
        contract.check(post(ACCOUNTS, mapOf("email" to email(), "password" to "short")), UNPROCESSABLE)

        val payload = registeredEvent(account.email).payloadAs<Json>()
        account.id = payload["accountId"] as String
        contract.check(post(VERIFY, emptyMap<String, Any>()), BAD_REQUEST)
        contract.check(post(VERIFY, mapOf("token" to "not-a-token")), UNPROCESSABLE)
        contract.check(post(VERIFY, mapOf("token" to payload["verificationToken"])), NO_CONTENT)
        return account
    }

    private fun sessions(account: TestAccount) {
        val unverified = email()
        post(ACCOUNTS, mapOf("email" to unverified, "password" to PASSWORD)).expectStatus().isAccepted
        contract.check(signIn(unverified, PASSWORD), FORBIDDEN)
        contract.check(post(SESSIONS, mapOf("email" to account.email)), BAD_REQUEST)
        contract.check(signIn(account.email, "Wrong-password-0"), UNAUTHORIZED)
        val source = freshSource()
        repeat(SOURCE_LIMIT) { signIn(email(), PASSWORD, source).expectStatus().isUnauthorized }
        contract.check(signIn(account.email, PASSWORD, source), TOO_MANY)
        val pair = contract.check(signIn(account.email, PASSWORD), OK)
        account.accessToken = pair["accessToken"] as String
        account.refreshToken = pair["refreshToken"] as String

        val refreshed = contract.check(post(REFRESH, mapOf("refreshToken" to account.refreshToken)), OK)
        contract.check(post(REFRESH, emptyMap<String, Any>()), BAD_REQUEST)
        contract.check(post(REFRESH, mapOf("refreshToken" to "unknown")), UNAUTHORIZED)
        contract.check(deleteAs(CURRENT_SESSION, "Bearer ${refreshed["accessToken"]}"), NO_CONTENT)
        contract.check(deleteAs(CURRENT_SESSION, null), UNAUTHORIZED)

        val again = contract.check(signIn(account.email, PASSWORD), OK)
        account.accessToken = again["accessToken"] as String
        account.refreshToken = again["refreshToken"] as String
    }

    private fun profile(account: TestAccount) {
        contract.check(get(ME, account.bearer), OK)
        contract.check(get(ME), UNAUTHORIZED)
        contract.check(putAs(ME, mapOf("displayName" to "Ada"), account.bearer), OK)
        contract.check(putAs(ME, emptyMap<String, Any>(), account.bearer), BAD_REQUEST)
        contract.check(putAs(ME, mapOf("displayName" to "Ada"), null), UNAUTHORIZED)
        contract.check(putAs(ME, mapOf("displayName" to "x".repeat(LONG_NAME)), account.bearer), UNPROCESSABLE)
    }

    private fun addresses(account: TestAccount) {
        val addresses = "$ME/addresses"
        val home = contract.check(post(addresses, address("Lisboa"), account.bearer), CREATED)["id"]
        contract.check(post(addresses, listOf("not an address"), account.bearer), BAD_REQUEST)
        contract.check(post(addresses, address("Porto"), null), UNAUTHORIZED)
        contract.check(post(addresses, address("Porto") + ("countryCode" to "XX"), account.bearer), UNPROCESSABLE)

        contract.check(get(addresses, account.bearer), OK)
        contract.check(get("$addresses?size=101", account.bearer), BAD_REQUEST)
        contract.check(get(addresses), UNAUTHORIZED)

        val item = "$addresses/$home"
        contract.check(putAs(item, address("Braga"), account.bearer), OK)
        contract.check(putAs(item, listOf("not an address"), account.bearer), BAD_REQUEST)
        contract.check(putAs(item, address("Braga"), null), UNAUTHORIZED)
        contract.check(putAs("$addresses/${UUID.randomUUID()}", address("Braga"), account.bearer), NOT_FOUND)
        contract.check(putAs(item, address("Braga") + ("countryCode" to "XX"), account.bearer), UNPROCESSABLE)

        contract.check(deleteAs(item, null), UNAUTHORIZED)
        contract.check(deleteAs("$addresses/${UUID.randomUUID()}", account.bearer), NOT_FOUND)
        contract.check(deleteAs(item, account.bearer), NO_CONTENT)
    }

    private fun preferencesAndPhone(account: TestAccount) {
        val preferences = "$ME/notification-preferences"
        contract.check(get(preferences, account.bearer), OK)
        contract.check(get(preferences), UNAUTHORIZED)
        contract.check(putAs(preferences, mapOf("channels" to listOf("email")), account.bearer), OK)
        contract.check(putAs(preferences, emptyMap<String, Any>(), account.bearer), BAD_REQUEST)
        contract.check(putAs(preferences, mapOf("channels" to listOf("email")), null), UNAUTHORIZED)
        contract.check(putAs(preferences, mapOf("channels" to listOf("email", "sms")), account.bearer), UNPROCESSABLE)

        val phone = "+55119" + (FIRST_NUMBER..LAST_NUMBER).random()
        val verifications = "$ME/phone-verifications"
        contract.check(post(verifications, mapOf("phoneNumber" to phone), account.bearer), ACCEPTED)
        contract.check(post(verifications, emptyMap<String, Any>(), account.bearer), BAD_REQUEST)
        contract.check(post(verifications, mapOf("phoneNumber" to phone), null), UNAUTHORIZED)
        val code = checkNotNull(CODE.find(awaitSms(phone))).groupValues[1]
        val confirm = "$verifications/confirm"
        contract.check(post(confirm, mapOf("code" to "x"), account.bearer), BAD_REQUEST)
        contract.check(post(confirm, mapOf("code" to code), null), UNAUTHORIZED)
        contract.check(post(confirm, mapOf("code" to wrong(code)), account.bearer), UNPROCESSABLE)
        contract.check(post(confirm, mapOf("code" to code), account.bearer), NO_CONTENT)
    }

    private fun passwordReset(account: TestAccount) {
        contract.check(post(RESETS, mapOf("email" to account.email)), ACCEPTED)
        contract.check(post(RESETS, emptyMap<String, Any>()), BAD_REQUEST)
        val event = recorded.awaitType(EventType.PasswordResetRequested) { it.aggregateId.toString() == account.id }
        val token = event.payloadAs<Json>()["resetToken"] as String
        val complete = "$RESETS/complete"
        contract.check(post(complete, mapOf("token" to token)), BAD_REQUEST)
        contract.check(post(complete, mapOf("token" to token, "newPassword" to "short")), UNPROCESSABLE)
        contract.check(post(complete, mapOf("token" to token, "newPassword" to NEW_PASSWORD)), NO_CONTENT)
    }

    private fun deletion() {
        val shopper = signedIn(registerAndVerify(TestAccount(email())))
        contract.check(deleteAs(ME, null), UNAUTHORIZED)
        contract.check(deleteAs(ME, shopper.bearer), NO_CONTENT)

        val operator = registerAndVerify(TestAccount(email()))
        database
            .sql("UPDATE account SET roles = 'shopper,operator' WHERE id = :id")
            .bind("id", UUID.fromString(operator.id))
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
        signedIn(operator)
        contract.check(deleteAs(ME, operator.bearer), FORBIDDEN)
    }

    private fun putAs(
        path: String,
        body: Any,
        bearer: String?,
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri(path)
            .headers { headers -> bearer?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
            .bodyValue(body)
            .exchange()

    private fun deleteAs(
        path: String,
        bearer: String?,
    ): WebTestClient.ResponseSpec =
        client
            .delete()
            .uri(path)
            .headers { headers -> bearer?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
            .exchange()

    private fun address(city: String): Map<String, Any> =
        mapOf(
            "label" to city,
            "recipientName" to "Conformance Shopper",
            "line1" to "Rua das Flores 12",
            "city" to city,
            "postalCode" to "1000-001",
            "countryCode" to "PT",
            "isDefault" to true,
        )

    /**
     * A fresh address on a public domain: the validator's `email` format checks the top-level domain against the IANA
     * list, which omits the reserved `.test` of the other integration tests.
     */
    private fun email(): String = "conformance+${UUID.randomUUID()}@example.com"

    /** A six-digit code that is not [code]. */
    private fun wrong(code: String): String = ((code.toInt() + 1) % MILLION).toString().padStart(CODE_DIGITS, '0')

    private companion object {
        const val ACCOUNTS = "$IDENTITY/accounts"
        const val VERIFY = "$IDENTITY/accounts/verify-email"
        const val SESSIONS = "$IDENTITY/sessions"
        const val REFRESH = "$IDENTITY/sessions/refresh"
        const val CURRENT_SESSION = "$IDENTITY/sessions/current"
        const val RESETS = "$IDENTITY/password-resets"
        const val FIRST_NUMBER = 10_000_000
        const val LAST_NUMBER = 99_999_999
        const val MILLION = 1_000_000
        const val CODE_DIGITS = 6

        /** Documented statuses this layer cannot produce, with the reason. */
        val DEFERRED: Map<String, String> =
            mapOf(
                "registerAccount 429" to GATEWAY_LIMIT,
                "verifyEmail 429" to GATEWAY_LIMIT,
                "refreshSession 429" to GATEWAY_LIMIT,
                "requestPasswordReset 429" to GATEWAY_LIMIT,
                "completePasswordReset 429" to GATEWAY_LIMIT,
                "requestPhoneVerification 429" to GATEWAY_LIMIT,
            )

        const val GATEWAY_LIMIT =
            "rate limiting of this operation is the gateway's (contracts/gateway-routes.md), covered by its tests; " +
                "identity throttles only sign-in itself (exercised above)"
    }
}

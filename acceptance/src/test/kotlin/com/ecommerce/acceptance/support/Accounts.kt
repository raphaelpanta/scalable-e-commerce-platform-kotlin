package com.ecommerce.acceptance.support

import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldNotBeBlank
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A shopper of one scenario. Holds credentials in memory only; [toString] never reveals them. */
class Shopper(
    val email: String,
    var password: String,
) {
    var accessToken: String? = null
    var accountId: String? = null
    var addressId: String? = null
    var registrationMessage: String? = null

    val bearer: String
        get() = checkNotNull(accessToken) { "$this is not signed in" }

    val isSignedIn: Boolean
        get() = accessToken != null

    override fun toString(): String = "Shopper($email)"

    companion object {
        /** A shopper with a unique address (`shopper+<uuid>@ecommerce.example`) and a policy-compliant password. */
        fun fresh(): Shopper = Shopper("shopper+${UUID.randomUUID()}@ecommerce.example", newPassword())

        fun newPassword(): String = "Shopper-${UUID.randomUUID().toString().take(PASSWORD_SALT)}-Pw!"

        private const val PASSWORD_SALT = 8
    }
}

/** Registration, email verification through Mailpit, sign-in and addresses (identity.yaml). */
class Accounts(
    private val api: ApiClient,
    private val mailpit: MailpitClient,
) {
    fun register(
        email: String,
        password: String,
    ): ApiResponse =
        untilNotThrottled {
            api.post(Paths.ACCOUNTS, mapOf("email" to email, "password" to password, "displayName" to "Acceptance"))
        }

    fun signIn(
        email: String,
        password: String,
    ): ApiResponse = api.post(Paths.SESSIONS, mapOf("email" to email, "password" to password))

    /** A shopper that registered and verified the email through the emailed token, not signed in. */
    fun registeredShopper(): Shopper {
        val shopper = Shopper.fresh()
        val registration = register(shopper.email, shopper.password)
        registration shouldHaveStatus Status.ACCEPTED
        shopper.registrationMessage = registration.body.string("message")
        val token = mailpit.awaitToken(shopper.email)
        withClue("verification token emailed to $shopper") { token.shouldNotBeBlank() }
        untilNotThrottled { api.post(Paths.VERIFY_EMAIL, mapOf("token" to token)) } shouldHaveStatus Status.NO_CONTENT
        return shopper
    }

    /** Signs [shopper] in (waiting out the gateway's rate limit if needed) and learns the account id. */
    fun signInSuccessfully(shopper: Shopper) {
        val response = untilNotThrottled { signIn(shopper.email, shopper.password) }
        response shouldHaveStatus Status.OK
        shopper.accessToken = response.body.requireString("accessToken")
        if (shopper.accountId == null) {
            val profile = api.get(Paths.ME, shopper.bearer)
            profile shouldHaveStatus Status.OK
            shopper.accountId = profile.body.requireString("id")
        }
    }

    fun signedInShopper(): Shopper = registeredShopper().also(::signInSuccessfully)

    /** Adds a delivery address in [city] and remembers it as the shopper's checkout address. */
    fun addAddress(
        shopper: Shopper,
        city: String = "Lisboa",
    ): ApiResponse {
        val response = api.post(Paths.ADDRESSES, address(city), shopper.bearer)
        if (response.status == Status.CREATED) shopper.addressId = response.body.requireString("id")
        return response
    }

    fun signedInShopperWithAddress(): Shopper =
        signedInShopper().also { addAddress(it) shouldHaveStatus Status.CREATED }

    /** A bearer token of the seeded operator, shared by the scenarios of one run while it is fresh. */
    fun operatorToken(): String = OperatorSession.token(this)

    fun operatorAccountId(): String = OperatorSession.accountId(this, api)

    internal fun signInOperator(): String {
        val response = untilNotThrottled { signIn(Environment.operatorEmail, Environment.operatorPassword) }
        withClue("the seeded operator (OPERATOR_EMAIL) must be able to sign in") {
            response shouldHaveStatus Status.OK
        }
        return response.body.requireString("accessToken")
    }

    companion object {
        /** First line of every address the suite saves; confirmation emails must repeat it. */
        const val ADDRESS_LINE = "Rua das Flores 12"

        /** An address input of identity.yaml (`AddressInput`) in [city]. */
        fun address(city: String): Map<String, Any> =
            mapOf(
                "label" to city,
                "recipientName" to "Acceptance Shopper",
                "line1" to ADDRESS_LINE,
                "city" to city,
                "postalCode" to "1000-001",
                "countryCode" to "PT",
                "isDefault" to true,
            )
    }
}

/** The operator's session, renewed before the 15-minute access token expires. */
private object OperatorSession {
    private val renewAfter: Duration = Duration.ofMinutes(10)
    private var token: String? = null
    private var accountId: String? = null
    private var obtainedAt: Instant = Instant.EPOCH

    @Synchronized
    fun token(accounts: Accounts): String {
        val current = token
        if (current != null && Instant.now().isBefore(obtainedAt.plus(renewAfter))) return current
        return accounts.signInOperator().also {
            token = it
            obtainedAt = Instant.now()
        }
    }

    @Synchronized
    fun accountId(
        accounts: Accounts,
        api: ApiClient,
    ): String =
        accountId ?: api.get(Paths.ME, token(accounts)).let { profile ->
            profile shouldHaveStatus Status.OK
            profile.body.requireString("id").also { accountId = it }
        }
}

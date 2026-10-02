package com.ecommerce.notification.infrastructure

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.HttpRequestBuilder
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.consumer.dsl.RegexpMatcher
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.notification.application.ContactLookup
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.EmailAddress
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.PhoneNumber
import com.ecommerce.notification.domain.RecipientContact
import com.ecommerce.notification.infrastructure.PactMessages.ACCOUNT_DELETED
import com.ecommerce.notification.infrastructure.PactMessages.ACCOUNT_REGISTERED
import com.ecommerce.notification.infrastructure.PactMessages.ACCOUNT_VERIFIED
import com.ecommerce.notification.infrastructure.PactMessages.ADA
import com.ecommerce.notification.infrastructure.PactMessages.CONSUMER
import com.ecommerce.notification.infrastructure.PactMessages.CORRELATION_ID
import com.ecommerce.notification.infrastructure.PactMessages.CORRELATION_REGEX
import com.ecommerce.notification.infrastructure.PactMessages.PASSWORD_RESET_REQUESTED
import com.ecommerce.notification.infrastructure.PactMessages.bodyOf
import com.ecommerce.notification.infrastructure.PactMessages.envelope
import com.ecommerce.notification.infrastructure.PactMessages.eventId
import com.ecommerce.notification.infrastructure.PactMessages.message
import com.ecommerce.notification.infrastructure.PactMessages.recipient
import com.ecommerce.notification.infrastructure.identity.IdentityContactClient
import com.ecommerce.platform.http.WebClientDefaults
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.testing.InternalToken
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

private const val PROVIDER = "identity"
private const val OK = 200
private const val NOT_FOUND = 404
private const val GRACE = "3b5d7f91-2c4e-4a68-9b0d-1f3a5c7e9b24"
private const val ANONYMISED = "c8a6e4d2-0b9f-4c71-8a35-6e2d4f8b1c07"
private const val UNKNOWN = "f0e1d2c3-b4a5-4968-8776-5a4b3c2d1e0f"
private const val JSON = "application/json"
private val JSON_TYPE = RegexpMatcher("^application/json(;.*)?$", JSON)
private val PROBLEM_TYPE = RegexpMatcher("^application/problem\\+json(;.*)?$", "application/problem+json")
private val CORRELATION = RegexpMatcher(CORRELATION_REGEX, CORRELATION_ID)

/**
 * Consumer `notification`, provider `identity` (pact-interactions.md §2.6 and §3.2): the contact lookup through
 * the real [IdentityContactClient], and every identity event through the real Kafka listener, each delivered twice
 * (FR-022: the duplicate has no effect).
 */
@Suppress("TooManyFunctions") // one pact method and one test per interaction of pact-interactions.md
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V4)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
class IdentityConsumerPactTest {
    @Autowired
    lateinit var harness: ConsumerHarness

    @BeforeEach
    fun reset() = harness.reset()

    @Pact(consumer = CONSUMER)
    fun contactEmailOnly(builder: PactBuilder): V4Pact =
        contact(
            builder,
            "a request for the contact details of an account with email only",
            "an account exists",
            mapOf(
                "accountId" to ADA,
                "email" to "ada@example.test",
                "phoneVerified" to false,
                "channels" to listOf("email"),
            ),
            ADA,
        ) { body ->
            body.stringValue("accountId", ADA)
            body.stringValue("email", "ada@example.test")
            body.booleanValue("phoneVerified", false)
            body.array("channels") { it.stringValue("email") }
            body.booleanValue("anonymised", false)
        }

    @Pact(consumer = CONSUMER)
    fun contactSmsOptedIn(builder: PactBuilder): V4Pact =
        contact(
            builder,
            "a request for the contact details of an account that opted in to SMS",
            "an account exists",
            mapOf(
                "accountId" to GRACE,
                "email" to "grace@example.test",
                "phoneNumber" to "+5511987654321",
                "phoneVerified" to true,
                "channels" to listOf("email", "sms"),
            ),
            GRACE,
        ) { body ->
            body.stringValue("accountId", GRACE)
            body.stringValue("email", "grace@example.test")
            body.stringValue("phoneNumber", "+5511987654321")
            body.booleanValue("phoneVerified", true)
            body.array("channels") {
                it.stringValue("email")
                it.stringValue("sms")
            }
            body.booleanValue("anonymised", false)
        }

    @Pact(consumer = CONSUMER)
    fun contactAnonymised(builder: PactBuilder): V4Pact =
        contact(
            builder,
            "a request for the contact details of an anonymised account",
            "an account is anonymised",
            mapOf("accountId" to ANONYMISED, "pseudonym" to "anon-4f9c2d71"),
            ANONYMISED,
        ) { body ->
            body.stringValue("accountId", ANONYMISED)
            body.stringMatcher("email", "^[^@\\s]+@anonymised\\.invalid$", "anon-4f9c2d71@anonymised.invalid")
            body.booleanValue("phoneVerified", false)
            body.array("channels") { }
            body.booleanValue("anonymised", true)
        }

    @Pact(consumer = CONSUMER)
    fun contactUnknown(builder: PactBuilder): V4Pact =
        builder
            .expectsToReceiveHttpInteraction("a request for the contact details of an unknown account") { http ->
                http
                    .state("no account exists", mapOf("accountId" to UNKNOWN))
                    .withRequest { request(it, UNKNOWN) }
                    .willRespondWith { response ->
                        response
                            .status(NOT_FOUND)
                            .header("Content-Type", PROBLEM_TYPE)
                            .header("X-Correlation-Id", CORRELATION)
                            .body(
                                newJsonBody { problem ->
                                    problem.stringValue("type", "https://ecommerce.example/problems/not-found")
                                    problem.stringValue("title", "Not found")
                                    problem.numberValue("status", NOT_FOUND)
                                    problem.stringType("detail", "Account not found.")
                                    problem.stringType("correlationId", CORRELATION_ID)
                                }.build(),
                            )
                    }
            }.toPact()

    @Pact(consumer = CONSUMER)
    fun accountRegistered(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an AccountRegistered event for a new account",
            "an account was registered",
            mapOf("accountId" to ADA, "email" to "ada@example.test"),
            Topic.ACCOUNT,
            ADA,
            envelope(ACCOUNT_REGISTERED, "AccountRegistered", "2026-10-02T10:00:00Z", ADA, PROVIDER) { payload ->
                payload.stringValue("accountId", ADA)
                recipient(payload)
                payload.stringType("verificationToken", "pact-example-verification-token")
            },
        )

    @Pact(consumer = CONSUMER)
    fun accountVerified(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an AccountVerified event for a verified account",
            "an account was verified",
            mapOf("accountId" to ADA, "email" to "ada@example.test"),
            Topic.ACCOUNT,
            ADA,
            envelope(ACCOUNT_VERIFIED, "AccountVerified", "2026-10-02T10:05:00Z", ADA, PROVIDER) { payload ->
                payload.stringValue("accountId", ADA)
            },
        )

    @Pact(consumer = CONSUMER)
    fun passwordResetRequested(builder: PactBuilder): V4Pact =
        message(
            builder,
            "a PasswordResetRequested event for an account",
            "a password reset was requested",
            mapOf("accountId" to ADA, "email" to "ada@example.test"),
            Topic.ACCOUNT,
            ADA,
            envelope(
                PASSWORD_RESET_REQUESTED,
                "PasswordResetRequested",
                "2026-10-02T10:10:00Z",
                ADA,
                PROVIDER,
            ) { payload ->
                payload.stringValue("accountId", ADA)
                recipient(payload)
                payload.stringType("resetToken", "pact-example-reset-token")
            },
        )

    @Pact(consumer = CONSUMER)
    fun accountDeleted(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an AccountDeleted event for a deleted account",
            "an account was deleted",
            mapOf("accountId" to ADA, "pseudonym" to "anon-4f9c2d71"),
            Topic.ACCOUNT,
            ADA,
            envelope(ACCOUNT_DELETED, "AccountDeleted", "2026-10-02T12:00:00Z", ADA, PROVIDER) { payload ->
                payload.stringValue("accountId", ADA)
            },
        )

    @Test
    @PactTestFor(pactMethod = "contactEmailOnly")
    fun `reads an email-only contact`(mockServer: MockServer) {
        lookup(mockServer, ADA) shouldBe
            ContactLookup.Found(contact("ada@example.test", null, setOf(NotificationChannel.EMAIL)))
    }

    @Test
    @PactTestFor(pactMethod = "contactSmsOptedIn")
    fun `reads a contact opted in to SMS`(mockServer: MockServer) {
        lookup(mockServer, GRACE) shouldBe
            ContactLookup.Found(
                contact(
                    "grace@example.test",
                    "+5511987654321",
                    setOf(NotificationChannel.EMAIL, NotificationChannel.SMS),
                ),
            )
    }

    @Test
    @PactTestFor(pactMethod = "contactAnonymised")
    fun `reads an anonymised contact`(mockServer: MockServer) {
        val found = lookup(mockServer, ANONYMISED) as ContactLookup.Found
        found.contact.anonymised shouldBe true
        found.contact.channels shouldBe emptySet()
    }

    @Test
    @PactTestFor(pactMethod = "contactUnknown")
    fun `reads an unknown account as unknown`(mockServer: MockServer) {
        lookup(mockServer, UNKNOWN) shouldBe ContactLookup.Unknown
    }

    @Test
    @PactTestFor(pactMethod = "accountRegistered", providerType = ProviderType.ASYNCH)
    fun `an AccountRegistered event twice queues one verification email`(message: V4Interaction.AsynchronousMessage) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ACCOUNT_REGISTERED)) shouldBe listOf("account_verification/email/queued")
        harness.bodiesOf(eventId(ACCOUNT_REGISTERED)).single() shouldContain
            "/verify?token=pact-example-verification-token"
        harness.recipient(UUID.fromString(ADA)) shouldBe "false/false"
        harness.processed(eventId(ACCOUNT_REGISTERED)) shouldBe 1L
    }

    @Test
    @PactTestFor(pactMethod = "accountVerified", providerType = ProviderType.ASYNCH)
    fun `an AccountVerified event twice updates the read model once and queues nothing`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ACCOUNT_VERIFIED)) shouldBe emptyList()
        harness.recipient(UUID.fromString(ADA)) shouldBe "true/false"
        harness.processed(eventId(ACCOUNT_VERIFIED)) shouldBe 1L
    }

    @Test
    @PactTestFor(pactMethod = "passwordResetRequested", providerType = ProviderType.ASYNCH)
    fun `a PasswordResetRequested event twice queues one reset email`(message: V4Interaction.AsynchronousMessage) {
        deliverTwice(message)
        harness.notificationsOf(eventId(PASSWORD_RESET_REQUESTED)) shouldBe listOf("password_reset/email/queued")
        harness.bodiesOf(eventId(PASSWORD_RESET_REQUESTED)).single() shouldContain
            "/reset-password?token=pact-example-reset-token"
        harness.processed(eventId(PASSWORD_RESET_REQUESTED)) shouldBe 1L
    }

    @Test
    @PactTestFor(pactMethod = "accountDeleted", providerType = ProviderType.ASYNCH)
    fun `an AccountDeleted event twice anonymises the recipient and suppresses queued notifications once`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        harness.queueNotificationFor(UUID.fromString(ADA))
        deliverTwice(message)
        harness.recipient(UUID.fromString(ADA)) shouldBe "false/true"
        harness.statesOf(UUID.fromString(ADA)) shouldBe listOf("suppressed/false")
        harness.processed(eventId(ACCOUNT_DELETED)) shouldBe 1L
    }

    private fun deliverTwice(message: V4Interaction.AsynchronousMessage) {
        repeat(2) { harness.deliver(Topic.ACCOUNT, ADA, bodyOf(message)) }
    }

    private fun lookup(
        mockServer: MockServer,
        accountId: String,
    ): ContactLookup {
        val client =
            IdentityContactClient(
                WebClientDefaults.internalClient(WebClient.builder(), mockServer.getUrl(), InternalToken.TEST),
            )
        return runBlocking { client.lookup(AccountId(UUID.fromString(accountId)), CORRELATION_ID) }
    }

    private fun contact(
        email: String,
        phone: String?,
        channels: Set<NotificationChannel>,
    ): RecipientContact =
        RecipientContact(
            email = EmailAddress.of(email).getOrNull(),
            phone = phone?.let { PhoneNumber.of(it).getOrNull() },
            phoneVerified = phone != null,
            channels = channels,
            anonymised = false,
        )

    private companion object {
        @Suppress("LongParameterList") // one value per column of the pact-interactions.md table
        fun contact(
            builder: PactBuilder,
            description: String,
            state: String,
            parameters: Map<String, Any>,
            accountId: String,
            body: (LambdaDslObject) -> Unit,
        ): V4Pact =
            builder
                .expectsToReceiveHttpInteraction(description) { http ->
                    http
                        .state(state, parameters)
                        .withRequest { request(it, accountId) }
                        .willRespondWith { response ->
                            response
                                .status(OK)
                                .header("Content-Type", JSON_TYPE)
                                .header("X-Correlation-Id", CORRELATION)
                                .body(json(body))
                        }
                }.toPact()

        fun json(body: (LambdaDslObject) -> Unit): DslPart = newJsonBody { body(it) }.build()

        fun request(
            request: HttpRequestBuilder,
            accountId: String,
        ): HttpRequestBuilder =
            request
                .method("GET")
                .path("/internal/accounts/$accountId/contact")
                .header("Accept", JSON)
                .header(InternalToken.HEADER, InternalToken.TEST)
                .header("X-Correlation-Id", CORRELATION)
    }
}

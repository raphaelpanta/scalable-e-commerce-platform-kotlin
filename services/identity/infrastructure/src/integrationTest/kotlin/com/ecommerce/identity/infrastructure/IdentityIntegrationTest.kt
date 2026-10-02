package com.ecommerce.identity.infrastructure

import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.messaging.envelope.payloadAs
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.MailpitContainer
import com.ecommerce.platform.testing.PostgresTestConfig
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistrar
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.Optional
import java.util.UUID

/** A JSON object as WebTestClient decodes it. */
typealias Json = Map<String, Any?>

val JSON_OBJECT = object : ParameterizedTypeReference<Json>() {}

const val PASSWORD = "S3cure-passphrase!"
const val IDENTITY = "/api/v1/identity"
const val ME = "$IDENTITY/accounts/me"
private const val FORWARDED_FOR = "X-Forwarded-For"
private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private val MAIL_TIMEOUT: Duration = Duration.ofSeconds(20)

/** Mailpit as the SMTP server of the simulated SMS channel; bean-managed so that it stops with the context. */
@TestConfiguration(proxyBeanMethods = false)
class MailpitTestConfig {
    @Bean
    fun mailpit(): MailpitContainer = MailpitContainer()

    @Bean
    fun mailProperties(mailpit: MailpitContainer): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.mail.host") { mailpit.smtpHost }
            registry.add("spring.mail.port") { mailpit.smtpPort }
        }
}

/** A registered account of a test: its email, password and (once known) id and tokens. */
class TestAccount(
    val email: String,
    var password: String = PASSWORD,
) {
    lateinit var id: String
    lateinit var accessToken: String
    lateinit var refreshToken: String

    val bearer: String get() = "Bearer $accessToken"
}

/**
 * Base of the integration tests: the identity service on a random port (management on the same port) with throwaway
 * PostgreSQL, Kafka and Mailpit containers, the test internal token and a recorder of the published events, with the
 * profile `seed` as in Compose (SEED=true). Every subclass shares one application context. Each sign-in comes from
 * its own `X-Forwarded-For` address unless a test names one, so that the per-source throttle of one test never
 * affects another.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["management.server.port=", "platform.security.internal-token=" + InternalToken.TEST],
)
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class, MailpitTestConfig::class)
@ActiveProfiles(SeedProfile.NAME)
@Suppress("TooManyFunctions") // request and fixture helpers shared by the integration tests
open class IdentityIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var database: DatabaseClient

    @Autowired
    protected lateinit var recorded: RecordedEvents

    @Autowired
    protected lateinit var mailpit: MailpitContainer

    protected val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()
    }

    protected fun freshEmail(): String = "shopper+${UUID.randomUUID()}@example.test"

    protected fun freshSource(): String = "source-${UUID.randomUUID()}"

    protected fun post(
        path: String,
        body: Any,
        bearer: String? = null,
        source: String = freshSource(),
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(path)
            .header(FORWARDED_FOR, source)
            .headers { headers -> bearer?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
            .bodyValue(body)
            .exchange()

    protected fun get(
        path: String,
        bearer: String? = null,
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .headers { headers -> bearer?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
            .exchange()

    protected fun put(
        path: String,
        body: Any,
        bearer: String,
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .bodyValue(body)
            .exchange()

    protected fun delete(
        path: String,
        bearer: String,
    ): WebTestClient.ResponseSpec =
        client
            .delete()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange()

    protected fun WebTestClient.ResponseSpec.json(status: Int): Json =
        expectStatus()
            .isEqualTo(status)
            .expectBody(JSON_OBJECT)
            .returnResult()
            .responseBody
            .orEmpty()

    protected fun signIn(
        email: String,
        password: String,
        source: String = freshSource(),
    ): WebTestClient.ResponseSpec =
        post("$IDENTITY/sessions", mapOf("email" to email, "password" to password), source = source)

    /** The `AccountRegistered` event of [email]. */
    protected fun registeredEvent(email: String): ReceivedEnvelope =
        recorded.awaitType(EventType.AccountRegistered) { recipientEmail(it) == email }

    protected fun recipientEmail(envelope: ReceivedEnvelope): String? =
        (envelope.payloadAs<Json>()["recipient"] as? Map<*, *>)?.get("email") as? String

    /** Registers [account] and verifies its email with the token of its `AccountRegistered` event. */
    protected fun registerAndVerify(account: TestAccount = TestAccount(freshEmail())): TestAccount {
        post(
            "$IDENTITY/accounts",
            mapOf("email" to account.email, "password" to account.password),
        ).expectStatus().isAccepted
        val payload = registeredEvent(account.email).payloadAs<Json>()
        account.id = payload["accountId"] as String
        post(
            "$IDENTITY/accounts/verify-email",
            mapOf("token" to payload["verificationToken"]),
        ).expectStatus().isNoContent
        return account
    }

    /** Signs [account] in and keeps its tokens. */
    protected fun signedIn(account: TestAccount = registerAndVerify()): TestAccount {
        val pair = signIn(account.email, account.password).json(OK)
        account.accessToken = pair["accessToken"] as String
        account.refreshToken = pair["refreshToken"] as String
        return account
    }

    /** One column of the account row of [email]. */
    protected fun accountColumn(
        email: String,
        column: String,
    ): String? =
        database
            .sql("SELECT $column FROM account WHERE email = :email")
            .bind("email", email)
            .map { row -> Optional.ofNullable(row.get(column)?.toString()) }
            .one()
            .block(QUERY_TIMEOUT)
            ?.orElse(null)

    /** The text of the simulated SMS mirrored to Mailpit for [phone], once it arrives. */
    protected fun awaitSms(phone: String): String {
        val address = "sms-${phone.removePrefix("+")}@sms.ecommerce.invalid"
        var text: String? = null
        await().atMost(MAIL_TIMEOUT).until {
            val message = mailpit.messages().firstOrNull { address in it.to }
            if (message != null) {
                message.subject shouldBe "SMS to $phone"
                text = mailpit.text(message.id)
            }
            text != null
        }
        return checkNotNull(text)
    }

    companion object {
        const val OK = 200
        const val CREATED = 201
        const val ACCEPTED = 202
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422
        const val TOO_MANY = 429
    }
}

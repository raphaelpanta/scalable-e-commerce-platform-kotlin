package com.ecommerce.payment.infrastructure

import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.testing.KafkaTestConfig
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.PostgresTestConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

private val CLIENT_TIMEOUT = java.time.Duration.ofSeconds(30)
private val QUERY_TIMEOUT = java.time.Duration.ofSeconds(10)
private const val SEND_TIMEOUT_SECONDS = 10L

const val CHARGES = "/internal/charges"
const val REFUNDS = "/internal/refunds"
const val PAYMENTS = "/api/v1/payments"
const val AUTHORIZATION = "Authorization"
const val IDEMPOTENCY_KEY = "Idempotency-Key"
const val CORRELATION_HEADER = "X-Correlation-Id"
const val APPROVE_TOKEN = "tok_sim_approve_4242"
const val UNREACHABLE_TOKEN = "tok_sim_unreachable"
const val SHOPPER = "shopper"
const val OPERATOR = "operator"

/** JSON members of an answer. */
val MEMBERS = object : ParameterizedTypeReference<Map<String, Any?>>() {}

/** A charge the order service could send: one order of [owner] for [amountMinor] paid with [token]. */
data class Charge(
    val orderId: UUID = UUID.randomUUID(),
    val owner: UUID = UUID.randomUUID(),
    val amountMinor: Long = 19_800,
    val token: String = APPROVE_TOKEN,
    val key: UUID = UUID.randomUUID(),
) {
    fun body(): Map<String, Any> =
        mapOf(
            "orderId" to orderId.toString(),
            "accountId" to owner.toString(),
            "amount" to mapOf("amountMinor" to amountMinor, "currency" to "BRL"),
            "paymentMethodRef" to token,
        )
}

/**
 * One application context for every integration test of the module: PostgreSQL and Kafka from Testcontainers, a
 * recorder of every topic, and a WireMock-served JWKS whose key signs the shopper and operator tokens.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class)
open class PaymentIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var recorded: RecordedEvents

    @Autowired
    protected lateinit var database: DatabaseClient

    @Autowired
    protected lateinit var kafka: KafkaTemplate<String, String>

    protected val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(CLIENT_TIMEOUT)
            .build()
    }

    /** A bearer token of [accountId] with [roles]. */
    protected fun bearer(
        accountId: UUID,
        vararg roles: String,
    ): String = "Bearer " + jwt.tokenFor(accountId, roles.toList())

    /** `POST /internal/charges` of [charge] (with the internal token unless [token] says otherwise). */
    protected fun postCharge(
        charge: Charge,
        body: Any = charge.body(),
        token: String? = InternalToken.TEST,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(CHARGES)
            .headers { headers ->
                token?.let { headers.set(InternalToken.HEADER, it) }
                headers.set(IDEMPOTENCY_KEY, charge.key.toString())
                headers.set(CORRELATION_HEADER, CORRELATION_ID)
            }.bodyValue(body)
            .exchange()

    /** `POST /internal/refunds` of [attemptId] of [charge] under [key]. */
    protected fun postRefund(
        charge: Charge,
        attemptId: String,
        key: UUID = UUID.randomUUID(),
        amountMinor: Long = charge.amountMinor,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(REFUNDS)
            .header(InternalToken.HEADER, InternalToken.TEST)
            .header(IDEMPOTENCY_KEY, key.toString())
            .bodyValue(
                mapOf(
                    "orderId" to charge.orderId.toString(),
                    "attemptId" to attemptId,
                    "amount" to mapOf("amountMinor" to amountMinor, "currency" to "BRL"),
                ),
            ).exchange()

    /** Charges [charge] and returns the answer body (the attempt). */
    protected fun charged(charge: Charge): Map<String, Any?> = body(postCharge(charge).expectStatus().isCreated)

    /** The body of a 2xx answer. */
    protected fun body(spec: WebTestClient.ResponseSpec): Map<String, Any?> =
        checkNotNull(spec.expectBody(MEMBERS).returnResult().responseBody)

    /** `GET` [path] as [accountId] with [role]. */
    protected fun read(
        path: String,
        accountId: UUID,
        role: String = SHOPPER,
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .header(AUTHORIZATION, bearer(accountId, role))
            .exchange()

    /** Publishes an order event to `order.order.v1`, as the order service's relay would. */
    protected fun publishOrderEvent(
        type: EventType,
        orderId: UUID,
        payload: Map<String, Any?>,
        eventId: UUID = UUID.randomUUID(),
    ): UUID {
        val envelope =
            Envelope(eventId, type.name, 1, Instant.now(), orderId, CORRELATION_ID, "order", payload)
        kafka
            .send(
                Topic.ORDER,
                orderId.toString(),
                EnvelopeJson.write(envelope),
            ).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return eventId
    }

    /** Rows of a one-column query, for assertions on the database. */
    protected fun column(
        sql: String,
        vararg parameters: Pair<String, Any>,
    ): List<Any?> =
        parameters
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .map { row, _ -> Optional.ofNullable(row.get(0)) }
            .all()
            .collectList()
            .block(QUERY_TIMEOUT)
            .orEmpty()
            .map { it.orElse(null) }

    companion object {
        const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
        val jwt: JwtFixture = JwtFixture().also { it.startJwks() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
            registry.add("platform.security.internal-token") { InternalToken.TEST }
        }
    }
}

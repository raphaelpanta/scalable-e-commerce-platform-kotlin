package com.ecommerce.order.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.testing.KafkaTestConfig
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.PostgresTestConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.Optional
import java.util.UUID

private val CLIENT_TIMEOUT: Duration = Duration.ofSeconds(30)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
const val ORDERS = "/api/v1/orders"
const val AUTHORIZATION = "Authorization"
const val IDEMPOTENCY_KEY = "Idempotency-Key"
const val SHOPPER = "shopper"
const val OPERATOR = "operator"

/** JSON members of an answer. */
val MEMBERS = object : ParameterizedTypeReference<Map<String, Any?>>() {}

/**
 * Asserts an order-specific RFC 9457 problem (`payment-declined`, `order-not-cancellable`, ...): its status, type,
 * content type and the echoed correlation id; returns its members.
 */
fun WebTestClient.ResponseSpec.expectOrderProblem(
    status: HttpStatus,
    slug: String,
): Map<String, Any?> {
    val result =
        expectStatus()
            .isEqualTo(status)
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(MEMBERS)
            .returnResult()
    val body = checkNotNull(result.responseBody)
    body["type"] shouldBe ProblemType.BASE_URI + slug
    body["status"] shouldBe status.value()
    body["correlationId"] shouldBe result.responseHeaders.getFirst("X-Correlation-Id")
    return body
}

/** The `id` of an order answer. */
fun orderIdOf(result: EntityExchangeResult<Map<String, Any?>>): String =
    checkNotNull(result.responseBody?.get("id")).toString()

/**
 * One application context for every integration test of the module: PostgreSQL and Kafka from Testcontainers,
 * WireMock standing in for cart, catalog, payment and identity (one server, their paths do not overlap), and a
 * WireMock-served JWKS whose key signs the shopper and operator tokens.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class)
open class OrderIntegrationTest {
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

    protected val stubs: Stubs get() = Stubs(dependencies)

    /** A bearer token of [accountId] with [roles]. */
    protected fun bearer(
        accountId: UUID,
        vararg roles: String,
    ): String = "Bearer " + jwt.tokenFor(accountId, roles.toList())

    /** `POST /api/v1/orders` for [shopper]. */
    protected fun placeOrder(
        shopper: Shopper,
        token: String = APPROVED_TOKEN,
        key: UUID = UUID.randomUUID(),
        revision: String = shopper.revision,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(ORDERS)
            .header(AUTHORIZATION, bearer(shopper.accountId, SHOPPER))
            .header(IDEMPOTENCY_KEY, key.toString())
            .bodyValue(
                mapOf(
                    "addressId" to shopper.addressId.toString(),
                    "cartRevision" to revision,
                    "paymentMethod" to mapOf("type" to "card", "token" to token),
                ),
            ).exchange()

    /** Places an approved order for a new shopper and returns the shopper and the order id. */
    protected fun paidOrder(shopper: Shopper = stubs.checkoutOf(Shopper())): Pair<Shopper, String> =
        shopper to
            orderIdOf(
                placeOrder(shopper)
                    .expectStatus()
                    .isCreated
                    .expectBody(MEMBERS)
                    .returnResult(),
            )

    /** `GET /api/v1/orders/{id}` as [accountId] with [role]. */
    protected fun getOrder(
        orderId: String,
        accountId: UUID,
        role: String = SHOPPER,
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("$ORDERS/$orderId")
            .header(AUTHORIZATION, bearer(accountId, role))
            .exchange()

    /** The order body of a 2xx answer. */
    protected fun body(spec: WebTestClient.ResponseSpec): Map<String, Any?> =
        checkNotNull(spec.expectBody(MEMBERS).returnResult().responseBody)

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
        val dependencies: WireMockServer = WireMockServer(options().dynamicPort()).apply { start() }
        val jwt: JwtFixture = JwtFixture().also { it.startJwks() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
            registry.add("platform.security.internal-token") { InternalToken.TEST }
            listOf("cart-url", "catalog-url", "payment-url", "identity-url").forEach { name ->
                registry.add("order.clients.$name") { dependencies.baseUrl() }
            }
        }
    }
}

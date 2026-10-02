package com.ecommerce.cart.infrastructure

import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A JSON object as WebTestClient decodes it. */
typealias Json = Map<String, Any?>

val JSON_OBJECT = object : ParameterizedTypeReference<Json>() {}

private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(15)

/**
 * Base of the integration tests: the cart service on a random port (management on the same port) with throwaway
 * PostgreSQL and Kafka containers, a JWKS served by [JwtFixture], the test internal token and a WireMock catalog
 * ([catalog]) whose pricing endpoints answer from the products registered with [CatalogStub.product]. Every
 * subclass shares one application context.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["management.server.port=", "platform.security.internal-token=" + InternalToken.TEST],
)
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class)
open class CartIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var database: DatabaseClient

    protected val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()
    }

    protected fun bearer(accountId: UUID): String = "Bearer " + jwt.tokenFor(accountId, setOf("shopper"))

    companion object {
        val jwt: JwtFixture = JwtFixture()
        val catalog: CatalogStub = CatalogStub()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
            registry.add("cart.catalog-url") { catalog.baseUrl }
        }
    }
}

/**
 * The catalog's internal pricing API on WireMock: `GET /internal/products/{id}/pricing` per registered product (an
 * unregistered id answers 404) and `POST /internal/products/pricing` answering every registered product (callers
 * look products up by id, so extra items are harmless).
 */
class CatalogStub {
    private val server = WireMockServer(options().dynamicPort()).also { it.start() }
    private val products = ConcurrentHashMap<UUID, Map<String, Any>>()
    private val mapper = JsonMapper.builder().build()

    val baseUrl: String get() = server.baseUrl()

    val wireMock: WireMockServer get() = server

    /** Registers (or changes) a product and returns its id. */
    @Suppress("LongParameterList") // every pricing field can be varied
    @Synchronized
    fun product(
        id: UUID = UUID.randomUUID(),
        priceMinor: Long = 2500,
        available: Int = 10,
        saleState: String = "active",
        sku: String = "CB-1KG",
        name: String = "Coffee beans",
    ): UUID {
        val pricing =
            mapOf(
                "productId" to id.toString(),
                "sku" to sku,
                "name" to name,
                "price" to mapOf("amountMinor" to priceMinor, "currency" to "BRL"),
                "available" to available,
                "saleState" to saleState,
            )
        products[id] = pricing
        server.stubFor(get(urlEqualTo("/internal/products/$id/pricing")).willReturn(json(pricing)))
        server.stubFor(
            post(urlEqualTo("/internal/products/pricing")).willReturn(json(mapOf("items" to products.values))),
        )
        return id
    }

    private fun json(body: Any) =
        aResponse()
            .withStatus(OK)
            .withHeader("Content-Type", "application/json")
            .withBody(mapper.writeValueAsString(body))

    private companion object {
        const val OK = 200
    }
}

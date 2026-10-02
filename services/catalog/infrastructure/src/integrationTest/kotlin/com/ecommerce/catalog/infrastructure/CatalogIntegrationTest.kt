package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID

/** A JSON object as WebTestClient decodes it. */
typealias Json = Map<String, Any?>

val JSON_OBJECT = object : ParameterizedTypeReference<Json>() {}

private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(15)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val CREATED = 201
private const val DEFAULT_PRICE = 1990L
private const val DEFAULT_STOCK = 5
private const val MAX_BODY_BYTES = 4 * 1024 * 1024

/** Account ids of the test callers. */
val OPERATOR_ID: UUID = UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22")
val SHOPPER_ID: UUID = UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d")

/**
 * Base of the integration tests: the catalog service on a random port (management on the same port) with throwaway
 * PostgreSQL and Kafka containers, a JWKS served by [JwtFixture] and the test internal token. Every subclass shares
 * one application context. Helpers create catalogue data through the operator API, as an operator would.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["management.server.port=", "platform.security.internal-token=" + InternalToken.TEST],
)
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class)
open class CatalogIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var database: DatabaseClient

    protected val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(RESPONSE_TIMEOUT)
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES) }
            .build()
    }

    protected fun operator(): String = "Bearer " + jwt.tokenFor(OPERATOR_ID, setOf("operator"))

    protected fun shopper(): String = "Bearer " + jwt.tokenFor(SHOPPER_ID, setOf("shopper"))

    /** A request to [uri] with [method], an optional bearer [token] and JSON [body]. */
    protected fun call(
        method: HttpMethod,
        uri: String,
        token: String? = null,
        body: Any? = null,
    ): WebTestClient.ResponseSpec {
        val spec =
            client
                .method(method)
                .uri(uri)
                .headers { headers -> token?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
        return (if (body == null) spec else spec.bodyValue(body)).exchange()
    }

    /** An internal request carrying [token] (the test internal token unless told otherwise). */
    protected fun internal(
        method: HttpMethod,
        uri: String,
        body: Any? = null,
        token: String? = InternalToken.TEST,
    ): WebTestClient.ResponseSpec {
        val spec =
            client
                .method(method)
                .uri(uri)
                .headers { headers -> token?.let { headers.set(InternalToken.HEADER, it) } }
        return (if (body == null) spec else spec.bodyValue(body)).exchange()
    }

    protected fun WebTestClient.ResponseSpec.json(status: Int): Json =
        expectStatus()
            .isEqualTo(status)
            .expectBody(JSON_OBJECT)
            .returnResult()
            .responseBody
            .shouldNotBeNull()

    /** Creates a category as the operator and returns its id. */
    protected fun category(
        name: String = "Category ${UUID.randomUUID()}",
        parentId: String? = null,
    ): String =
        call(HttpMethod.POST, CATEGORIES, operator(), mapOf("name" to name, "parentId" to parentId))
            .json(CREATED)["id"]
            .toString()

    /** Creates an active product as the operator and returns its id. */
    @Suppress("LongParameterList") // every attribute of a product can be varied
    protected fun product(
        categoryId: String = category(),
        name: String = "Product ${UUID.randomUUID()}",
        priceMinor: Long = DEFAULT_PRICE,
        stock: Int = DEFAULT_STOCK,
        description: String = "Created by an integration test.",
        sku: String? = null,
    ): String {
        val body =
            mapOf(
                "name" to name,
                "description" to description,
                "price" to mapOf("amountMinor" to priceMinor, "currency" to "BRL"),
                "categoryId" to categoryId,
                "initialStock" to stock,
                "sku" to sku,
            )
        return call(HttpMethod.POST, PRODUCTS, operator(), body).json(CREATED)["id"].toString()
    }

    /** `onHand/reserved` of [productId] straight from the database. */
    protected fun stockOf(productId: String): String? =
        database
            .sql("SELECT on_hand, reserved FROM inventory_levels WHERE product_id = :id")
            .bind("id", UUID.fromString(productId))
            .map { row ->
                val onHand = row.get("on_hand", Int::class.javaObjectType)
                val reserved = row.get("reserved", Int::class.javaObjectType)
                "$onHand/$reserved"
            }.one()
            .block(QUERY_TIMEOUT)

    /** Runs [sql] with [bindings]. */
    protected fun execute(
        sql: String,
        bindings: Map<String, Any> = emptyMap(),
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    companion object {
        const val PRODUCTS = "/api/v1/catalog/products"
        const val CATEGORIES = "/api/v1/catalog/categories"
        val jwt: JwtFixture = JwtFixture()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
        }
    }
}

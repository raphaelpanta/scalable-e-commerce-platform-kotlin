package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

private const val SEEDED_PRODUCTS = 20
private const val ACTIVE_PRODUCTS = 19
private const val SEEDED_CATEGORIES = 3
private const val TRAIL_SHOES_STOCK = 50
private const val ESPRESSO = "9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01"
private const val CERAMIC_MUG = "b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44"
private const val VINTAGE_KETTLE = "a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

/**
 * T028: `SEED=true` activates the `seed` profile, whose repeatable migration loads 3 categories and 20 products with
 * stock, one of them withdrawn (hidden from shoppers) and one without stock (services/catalog/README.md).
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["management.server.port=", "SEED=true", "platform.security.internal-token=" + InternalToken.TEST],
)
@Import(PostgresTestConfig::class, KafkaTestConfig::class)
class SeedProfileIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(RESPONSE_SECONDS))
            .build()
    }

    private fun get(
        uri: String,
        token: String? = null,
    ): Json =
        client
            .get()
            .uri(uri)
            .headers { headers -> token?.let { headers.set(InternalToken.HEADER, it) } }
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(JSON_OBJECT)
            .returnResult()
            .responseBody
            .shouldNotBeNull()

    @Test
    fun `the seed profile loads three categories and twenty products, one withdrawn and one out of stock`() {
        get("/api/v1/catalog/categories")["totalItems"] shouldBe SEEDED_CATEGORIES
        get("/api/v1/catalog/products?size=100")["totalItems"] shouldBe ACTIVE_PRODUCTS

        val priced =
            get(
                "/internal/products/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21/pricing",
                InternalToken.TEST,
            )
        priced["sku"] shouldBe "TRS-001"
        priced["available"] shouldBe TRAIL_SHOES_STOCK
        get("/api/v1/catalog/products/$CERAMIC_MUG")["availability"] shouldBe mapOf("inStock" to false)
        get("/api/v1/catalog/products/$ESPRESSO")["availability"] shouldBe mapOf("inStock" to true)
        client
            .get()
            .uri("/api/v1/catalog/products/$VINTAGE_KETTLE")
            .exchange()
            .expectProblem(ProblemType.NOT_FOUND)
        get("/internal/products/$VINTAGE_KETTLE/pricing", InternalToken.TEST)["saleState"] shouldBe "withdrawn"

        @Suppress("UNCHECKED_CAST")
        val products = get("/api/v1/catalog/products?size=100")["items"] as List<Json>
        products.all { product -> (product["images"] as List<*>).size == 1 } shouldBe true
        products.map { it["name"] } shouldContainAll listOf("Espresso Machine", "Coffee Beans 1kg", "Ceramic Mug")
        (products.size + 1) shouldBe SEEDED_PRODUCTS
    }

    private companion object {
        const val RESPONSE_SECONDS = 15L
    }
}

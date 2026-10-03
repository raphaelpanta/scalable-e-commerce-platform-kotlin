package com.ecommerce.cart.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

private const val BAD_REQUEST = 400
private const val ESPRESSO_PRICE = 14900L
private const val BEANS_PRICE = 2450L

/** cart-internal.yaml: order reads and clears an account cart, only with `X-Internal-Token`. */
class InternalCartApiIT : CartIntegrationTest() {
    private fun internal(
        method: String,
        uri: String,
        token: String? = InternalToken.TEST,
    ): WebTestClient.ResponseSpec =
        client
            .method(
                org.springframework.http.HttpMethod
                    .valueOf(method),
            ).uri(uri)
            .headers { headers -> token?.let { headers.set(InternalToken.HEADER, it) } }
            .exchange()

    private fun accountCartOf(account: UUID): Json =
        internal("GET", "/internal/carts/by-account/$account")
            .expectStatus()
            .isOk
            .expectBody(JSON_OBJECT)
            .returnResult()
            .responseBody
            .shouldNotBeNull()

    private fun addToAccount(
        account: UUID,
        productId: UUID,
        quantity: Int,
    ) {
        client
            .post()
            .uri("/api/v1/cart/lines")
            .header(HttpHeaders.AUTHORIZATION, bearer(account))
            .bodyValue(mapOf("productId" to productId, "quantity" to quantity))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Void::class.java) // consume the body so the client releases it
    }

    @Test
    fun `order reads the account cart with its revision and the total at add prices`() {
        val account = UUID.randomUUID()
        val espresso = catalog.product(priceMinor = ESPRESSO_PRICE, sku = "ESP-MACH-01", name = "Espresso Machine")
        val beans = catalog.product(priceMinor = BEANS_PRICE, sku = "CB-1KG", name = "Coffee Beans 1kg")
        addToAccount(account, espresso, 1)
        addToAccount(account, beans, 2)
        val public =
            client
                .get()
                .uri("/api/v1/cart")
                .header(HttpHeaders.AUTHORIZATION, bearer(account))
                .exchange()
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
                .shouldNotBeNull()

        val cart = accountCartOf(account)

        cart["cartId"] shouldBe public["id"]
        cart["revision"] shouldBe public["revision"]
        cart["total"] shouldBe mapOf("amountMinor" to (ESPRESSO_PRICE + 2 * BEANS_PRICE).toInt(), "currency" to "BRL")
        @Suppress("UNCHECKED_CAST")
        val lines = cart["lines"] as List<Json>
        lines.map { it - "lineId" } shouldBe
            listOf(
                mapOf(
                    "productId" to espresso.toString(),
                    "sku" to "ESP-MACH-01",
                    "name" to "Espresso Machine",
                    "quantity" to 1,
                    "priceAtAdd" to mapOf("amountMinor" to ESPRESSO_PRICE.toInt(), "currency" to "BRL"),
                ),
                mapOf(
                    "productId" to beans.toString(),
                    "sku" to "CB-1KG",
                    "name" to "Coffee Beans 1kg",
                    "quantity" to 2,
                    "priceAtAdd" to mapOf("amountMinor" to BEANS_PRICE.toInt(), "currency" to "BRL"),
                ),
            )
    }

    @Test
    fun `order clears the account cart idempotently, keeping the cart`() {
        val account = UUID.randomUUID()
        addToAccount(account, catalog.product(), 1)
        val before = accountCartOf(account)

        internal("POST", "/internal/carts/by-account/$account/clear")
            .expectStatus()
            .isNoContent
            .expectBody()
            .isEmpty
        internal("POST", "/internal/carts/by-account/$account/clear").expectStatus().isNoContent
        internal("POST", "/internal/carts/by-account/${UUID.randomUUID()}/clear").expectStatus().isNoContent

        val after = accountCartOf(account)
        after["cartId"] shouldBe before["cartId"]
        after["revision"] shouldNotBe before["revision"]
        (after["lines"] as List<*>).shouldBeEmpty()
        after["total"] shouldBe mapOf("amountMinor" to 0, "currency" to "BRL")
    }

    @Test
    fun `an account without a cart is not found and malformed ids are refused`() {
        val problem =
            internal(
                "GET",
                "/internal/carts/by-account/${UUID.randomUUID()}",
            ).expectProblem(ProblemType.NOT_FOUND)

        problem["detail"] shouldBe "No cart exists for the account."
        internal("GET", "/internal/carts/by-account/not-a-uuid").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `the internal endpoints refuse a missing or wrong internal token`() {
        val account = UUID.randomUUID()
        addToAccount(account, catalog.product(), 1)

        internal("GET", "/internal/carts/by-account/$account", token = null).expectProblem(ProblemType.UNAUTHORIZED)
        internal("GET", "/internal/carts/by-account/$account", token = "wrong").expectProblem(ProblemType.UNAUTHORIZED)
        internal(
            "POST",
            "/internal/carts/by-account/$account/clear",
            token = null,
        ).expectProblem(ProblemType.UNAUTHORIZED)
        (accountCartOf(account)["lines"] as List<*>).size shouldBe 1
    }
}

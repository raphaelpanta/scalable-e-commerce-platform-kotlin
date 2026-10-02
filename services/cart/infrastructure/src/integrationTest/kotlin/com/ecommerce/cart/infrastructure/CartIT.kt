package com.ecommerce.cart.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.core.values.SecretToken
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID

private const val CART_TOKEN = "X-Cart-Token"
private const val UNPROCESSABLE = 422
private const val BAD_REQUEST = 400
private const val SERVER_ERROR = 500
private const val COFFEE_PRICE = 2500L
private const val NEW_PRICE = 2750L
private const val STOCK = 10
private const val MORE = 3
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

/** Public cart API against PostgreSQL and a stubbed catalogue: token issuance, anonymous and account flows (T040). */
@Suppress("TooManyFunctions") // one test per behaviour of cart.yaml plus small request helpers
class CartIT : CartIntegrationTest() {
    private fun addLine(
        productId: UUID,
        quantity: Int,
        token: String? = null,
        authorization: String? = null,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/v1/cart/lines")
            .headers { headers ->
                token?.let { headers.set(CART_TOKEN, it) }
                authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
            }.bodyValue(mapOf("productId" to productId, "quantity" to quantity))
            .exchange()

    private fun request(
        method: String,
        uri: String,
        token: String? = null,
        authorization: String? = null,
        body: Any? = null,
    ): WebTestClient.ResponseSpec {
        val spec =
            client
                .method(
                    org.springframework.http.HttpMethod
                        .valueOf(method),
                ).uri(uri)
                .headers { headers ->
                    token?.let { headers.set(CART_TOKEN, it) }
                    authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
                }
        return (if (body == null) spec else spec.bodyValue(body)).exchange()
    }

    private fun WebTestClient.ResponseSpec.okCart(): Json =
        expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody(JSON_OBJECT)
            .returnResult()
            .responseBody
            .shouldNotBeNull()

    private fun newAnonymousCart(
        productId: UUID,
        quantity: Int,
    ): Pair<String, Json> {
        val result =
            addLine(productId, quantity)
                .expectStatus()
                .isCreated
                .expectBody(JSON_OBJECT)
                .returnResult()
        val token = result.responseHeaders.getFirst(CART_TOKEN).shouldNotBeNull()
        return token to result.responseBody.shouldNotBeNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Json.lines(): List<Json> = this["lines"] as List<Json>

    @Suppress("UNCHECKED_CAST")
    private fun Json.money(name: String): Long = ((this[name] as Json)["amountMinor"] as Number).toLong()

    @Test
    fun `the first anonymous write issues an opaque token of which only the hash is stored`() {
        val product = catalog.product(priceMinor = COFFEE_PRICE)

        val (token, cart) = newAnonymousCart(product, 2)

        token shouldMatch Regex("[A-Za-z0-9_-]{43}")
        val line = cart.lines().single()
        line["productId"] shouldBe product.toString()
        line["productName"] shouldBe "Coffee beans"
        line["quantity"] shouldBe 2
        line.money("priceAtAdd") shouldBe COFFEE_PRICE
        line.money("currentPrice") shouldBe COFFEE_PRICE
        line["priceChanged"] shouldBe false
        line.money("lineTotal") shouldBe 2 * COFFEE_PRICE
        cart.money("total") shouldBe 2 * COFFEE_PRICE
        (cart["revision"] as String) shouldStartWith "rev-"
        val stored =
            database
                .sql("SELECT token_hash FROM cart WHERE id = :id")
                .bind("id", UUID.fromString(cart["id"] as String))
                .map { row -> checkNotNull(row.get("token_hash", String::class.java)) }
                .one()
                .block(QUERY_TIMEOUT)
        stored shouldBe SecretToken.sha256Hex(token)
        stored shouldNotBe token
        catalog.wireMock.verify(
            getRequestedFor(urlEqualTo("/internal/products/$product/pricing"))
                .withHeader("X-Internal-Token", equalTo("pact-internal-token")),
        )
    }

    @Test
    fun `an anonymous read without a token is an empty cart and issues no token`() {
        val result =
            client
                .get()
                .uri("/api/v1/cart")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(JSON_OBJECT)
                .returnResult()

        result.responseHeaders.getFirst(CART_TOKEN).shouldBeNull()
        val cart = result.responseBody.shouldNotBeNull()
        cart["id"] shouldBe "00000000-0000-0000-0000-000000000000"
        cart.lines().shouldBeEmpty()
        cart["total"] shouldBe mapOf("amountMinor" to 0, "currency" to "BRL")
        (cart["revision"] as String) shouldStartWith "rev-"
    }

    @Test
    fun `an anonymous shopper adds and changes lines, each change giving a new revision`() {
        val coffee = catalog.product(available = STOCK)
        val (token, first) = newAnonymousCart(coffee, 2)

        val summed =
            addLine(coffee, MORE, token)
                .expectStatus()
                .isCreated
                .expectHeader()
                .doesNotExist(CART_TOKEN)
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        summed.lines().single()["quantity"] shouldBe 2 + MORE
        summed["id"] shouldBe first["id"]
        summed["revision"] shouldNotBe first["revision"]
        val viewed = request("GET", "/api/v1/cart", token).okCart()
        viewed["revision"] shouldBe summed["revision"]
        request("GET", "/api/v1/cart", token).okCart()["revision"] shouldBe viewed["revision"]

        val lineId = summed.lines().single()["id"]
        val changed = request("PUT", "/api/v1/cart/lines/$lineId", token, body = mapOf("quantity" to 1)).okCart()
        changed.lines().single()["quantity"] shouldBe 1
        changed["revision"] shouldNotBe viewed["revision"]
        request(
            "PUT",
            "/api/v1/cart/lines/$lineId",
            token,
            body = mapOf("quantity" to 0),
        ).okCart().lines().shouldBeEmpty()
    }

    @Test
    fun `an anonymous shopper removes lines and clears the cart idempotently`() {
        val coffee = catalog.product(available = STOCK)
        val (token, _) = newAnonymousCart(coffee, 1)

        val readded =
            addLine(coffee, 1, token)
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        val removed = request("DELETE", "/api/v1/cart/lines/${readded.lines().single()["id"]}", token).okCart()
        removed.lines().shouldBeEmpty()
        removed.money("total") shouldBe 0

        addLine(coffee, 1, token).expectStatus().isCreated
        request("DELETE", "/api/v1/cart", token).expectStatus().isNoContent
        request("DELETE", "/api/v1/cart", token).expectStatus().isNoContent
        request("GET", "/api/v1/cart", token).okCart().lines().shouldBeEmpty()
    }

    @Test
    fun `more units than in stock are refused stating the available quantity`() {
        val coffee = catalog.product(available = STOCK)

        val problem = addLine(coffee, STOCK + 1).expectProblem(ProblemType.INSUFFICIENT_STOCK, UNPROCESSABLE)

        (problem["detail"] as String) shouldContain "Only 10 units are available"
        problem["errors"] shouldBe listOf(mapOf("field" to "quantity", "message" to "available quantity: 10"))
        val (token, cart) = newAnonymousCart(coffee, STOCK)
        addLine(coffee, 1, token).expectProblem(ProblemType.INSUFFICIENT_STOCK, UNPROCESSABLE)
        val lineId = cart.lines().single()["id"]
        request("PUT", "/api/v1/cart/lines/$lineId", token, body = mapOf("quantity" to STOCK + 1))
            .expectProblem(ProblemType.INSUFFICIENT_STOCK, UNPROCESSABLE)
    }

    @Test
    fun `unknown, withdrawn and out-of-stock products and malformed requests are refused`() {
        val withdrawn = catalog.product(saleState = "withdrawn")
        val soldOut = catalog.product(available = 0)

        addLine(UUID.randomUUID(), 1).expectProblem(ProblemType.NOT_FOUND)
        addLine(withdrawn, 1).expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        addLine(soldOut, 1).expectProblem(ProblemType.INSUFFICIENT_STOCK, UNPROCESSABLE)
        addLine(soldOut, 0).expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        request(
            "POST",
            "/api/v1/cart/lines",
            body = mapOf("quantity" to 1),
        ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        request("PUT", "/api/v1/cart/lines/not-a-uuid", body = mapOf("quantity" to 1))
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        request("PUT", "/api/v1/cart/lines/${UUID.randomUUID()}", body = mapOf("quantity" to 1))
            .expectProblem(ProblemType.NOT_FOUND)
        request("GET", "/api/v1/cart", token = SecretToken.generate().value).expectProblem(ProblemType.NOT_FOUND)
    }

    @Test
    fun `a price change is shown with the current price, flagged, and changes the revision`() {
        val coffee = catalog.product(priceMinor = COFFEE_PRICE)
        val (token, added) = newAnonymousCart(coffee, 1)

        catalog.product(coffee, priceMinor = NEW_PRICE)
        val viewed = request("GET", "/api/v1/cart", token).okCart()

        val line = viewed.lines().single()
        line.money("priceAtAdd") shouldBe COFFEE_PRICE
        line.money("currentPrice") shouldBe NEW_PRICE
        line["priceChanged"] shouldBe true
        line.money("lineTotal") shouldBe NEW_PRICE
        viewed.money("total") shouldBe NEW_PRICE
        viewed["revision"] shouldNotBe added["revision"]
    }

    @Test
    fun `a signed-in shopper uses the account cart whatever X-Cart-Token says`() {
        val account = UUID.randomUUID()
        val coffee = catalog.product()
        val (anonymousToken, _) = newAnonymousCart(coffee, 1)

        addLine(
            coffee,
            2,
            anonymousToken,
            bearer(account),
        ).expectStatus().isCreated.expectHeader().doesNotExist(CART_TOKEN)

        val cart = request("GET", "/api/v1/cart", anonymousToken, bearer(account)).okCart()
        cart.lines().single()["quantity"] shouldBe 2
        request("GET", "/api/v1/cart", anonymousToken).okCart().lines().single()["quantity"] shouldBe 1
        request("GET", "/api/v1/cart", authorization = "Bearer not-a-jwt").expectProblem(ProblemType.UNAUTHORIZED)
    }

    @Test
    fun `an unreachable catalogue answers 503`() {
        val broken = UUID.randomUUID()
        catalog.wireMock.stubFor(
            get(urlEqualTo("/internal/products/$broken/pricing")).willReturn(aResponse().withStatus(SERVER_ERROR)),
        )

        addLine(broken, 1).expectProblem(ProblemType.UNAVAILABLE)
        request("GET", "/api/v1/cart").okCart().lines() shouldHaveSize 0
    }
}

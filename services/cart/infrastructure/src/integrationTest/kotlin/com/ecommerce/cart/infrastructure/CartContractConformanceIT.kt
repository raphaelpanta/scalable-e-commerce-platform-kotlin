package com.ecommerce.cart.infrastructure

import com.ecommerce.conformance.OpenApiContract
import com.ecommerce.platform.core.values.SecretToken
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val NO_CONTENT = 204
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val UNPROCESSABLE = 422
private const val SERVER_ERROR = 500
private const val UNAVAILABLE = 503
private const val CART_TOKEN = "X-Cart-Token"
private const val CART = "/api/v1/cart"
private const val LINES = "/api/v1/cart/lines"
private const val MERGE = "/api/v1/cart/merge"
private const val INVALID_BEARER = "Bearer not-a-jwt"

/**
 * Every public operation of `contracts/openapi/cart.yaml`, with its success and documented error statuses, exercised
 * against the running service (catalogue stubbed) and validated request by request (pact-matrix rule 3, constitution
 * Principle V). Statuses the service cannot produce on its own are deferred with the reason.
 */
class CartContractConformanceIT : CartIntegrationTest() {
    private val contract = OpenApiContract.of("cart")

    @Test
    fun `the public operations conform to cart yaml`() {
        val coffee = catalog.product(priceMinor = PRICE, available = STOCK)
        anonymousCart(coffee)
        accountCartAndMerge(coffee)
        contract.verify(DEFERRED)
    }

    private fun anonymousCart(coffee: UUID) {
        contract.check(request(GET, CART), OK)
        val added = request(POST, LINES, body = line(coffee, 2))
        val token = added.returnResult(ByteArray::class.java).responseHeaders.getFirst(CART_TOKEN)
        val cart = contract.check(request(POST, LINES, token = token, body = line(coffee, 1)), CREATED)
        val lineId = lines(cart).first()["id"].toString()

        contract.check(request(POST, LINES, token = token, body = mapOf("quantity" to 1)), BAD_REQUEST)
        contract.check(request(POST, LINES, authorization = INVALID_BEARER, body = line(coffee, 1)), UNAUTHORIZED)
        contract.check(request(POST, LINES, token = token, body = line(UUID.randomUUID(), 1)), NOT_FOUND)
        contract.check(request(POST, LINES, token = token, body = line(coffee, STOCK + 1)), UNPROCESSABLE)
        val broken = UUID.randomUUID()
        catalog.wireMock.stubFor(
            get(urlEqualTo("/internal/products/$broken/pricing")).willReturn(aResponse().withStatus(SERVER_ERROR)),
        )
        contract.check(request(POST, LINES, token = token, body = line(broken, 1)), UNAVAILABLE)

        contract.check(request(GET, CART, token = token), OK)
        contract.check(request(GET, CART, token = MALFORMED_TOKEN), NOT_FOUND)
        contract.check(request(GET, CART, authorization = INVALID_BEARER), UNAUTHORIZED)
        contract.check(request(GET, CART, token = SecretToken.generate().value), NOT_FOUND)

        val item = "$LINES/$lineId"
        contract.check(request(PUT, item, token = token, body = mapOf("quantity" to 2)), OK)
        contract.check(request(PUT, "$LINES/not-a-uuid", token = token, body = mapOf("quantity" to 1)), BAD_REQUEST)
        contract.check(request(PUT, item, authorization = INVALID_BEARER, body = mapOf("quantity" to 1)), UNAUTHORIZED)
        val unknownLine = "$LINES/${UUID.randomUUID()}"
        contract.check(request(PUT, unknownLine, token = token, body = mapOf("quantity" to 1)), NOT_FOUND)
        contract.check(request(PUT, item, token = token, body = mapOf("quantity" to STOCK + 1)), UNPROCESSABLE)

        contract.check(request(DELETE, "$LINES/not-a-uuid", token = token), BAD_REQUEST)
        contract.check(request(DELETE, item, authorization = INVALID_BEARER), UNAUTHORIZED)
        contract.check(request(DELETE, "$LINES/${UUID.randomUUID()}", token = token), NOT_FOUND)
        contract.check(request(DELETE, item, token = token), OK)

        contract.check(request(DELETE, CART, authorization = INVALID_BEARER), UNAUTHORIZED)
        contract.check(request(DELETE, CART, token = SecretToken.generate().value), NOT_FOUND)
        contract.check(request(DELETE, CART, token = token), NO_CONTENT)
    }

    private fun accountCartAndMerge(coffee: UUID) {
        val account = UUID.randomUUID()
        contract.check(request(POST, LINES, authorization = bearer(account), body = line(coffee, 1)), CREATED)
        val token =
            request(POST, LINES, body = line(coffee, 2))
                .returnResult(ByteArray::class.java)
                .responseHeaders
                .getFirst(CART_TOKEN)

        contract.check(request(POST, MERGE, token = token), UNAUTHORIZED)
        contract.check(request(POST, MERGE, authorization = bearer(account)), BAD_REQUEST)
        contract.check(
            request(POST, MERGE, token = token, authorization = "Bearer " + jwt.tokenFor(account, emptySet())),
            FORBIDDEN,
        )
        contract.check(request(POST, MERGE, token = token, authorization = bearer(account)), OK)
        contract.check(request(POST, MERGE, token = token, authorization = bearer(account)), NOT_FOUND)
    }

    private fun request(
        method: HttpMethod,
        uri: String,
        token: String? = null,
        authorization: String? = null,
        body: Any? = null,
    ): WebTestClient.ResponseSpec {
        val spec =
            client
                .method(method)
                .uri(uri)
                .headers { headers ->
                    token?.let { headers.set(CART_TOKEN, it) }
                    authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
                }
        return (if (body == null) spec else spec.bodyValue(body)).exchange()
    }

    private fun line(
        productId: UUID,
        quantity: Int,
    ): Map<String, Any> = mapOf("productId" to productId, "quantity" to quantity)

    @Suppress("UNCHECKED_CAST")
    private fun lines(cart: Map<String, Any?>): List<Map<String, Any?>> = cart["lines"] as List<Map<String, Any?>>

    private companion object {
        const val PRICE = 2500L
        const val STOCK = 10
        const val MALFORMED_TOKEN = "not a token"
        const val NOTHING_MALFORMED =
            "the operation has no input that can be malformed: an unknown or malformed " +
                "X-Cart-Token answers 404"

        /** Documented statuses this layer cannot produce, with the reason. */
        val DEFERRED: Map<String, String> =
            mapOf(
                "* 429" to "rate limiting is the gateway's (contracts/gateway-routes.md), covered by its tests",
                "mergeCart 409" to "two merges of one token at the same instant cannot be produced deterministically",
                "getCart 400" to NOTHING_MALFORMED,
                "clearCart 400" to NOTHING_MALFORMED,
            )
    }
}

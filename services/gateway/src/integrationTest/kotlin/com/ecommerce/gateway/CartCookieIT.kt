package com.ecommerce.gateway

import com.ecommerce.gateway.browser.AesGcmSessionSealer
import com.ecommerce.gateway.browser.SealedSession
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

private const val JWKS_PATH = "/.well-known/jwks.json"
private const val CART = "/api/v1/cart"
private const val LINES = "/api/v1/cart/lines"
private const val MERGE = "/api/v1/cart/merge"
private const val CART_TOKEN = "X-Cart-Token"
private const val BROWSER_SESSION = "X-Browser-Session"
private const val CART_COOKIE = "cart"
private const val HOST_CART_COOKIE = "__Host-cart"
private const val CART_ATTRIBUTES = "HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000"
private const val CART_DELETION = "$CART_COOKIE=; Max-Age=0; HttpOnly; SameSite=Lax; Path=/"
private const val UPSTREAM_TOKEN = "tok-cart-1"
private const val PRODUCT_ID = "9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01"
private val KEY_BYTES = ByteArray(AesGcmSessionSealer.KEY_BYTES) { (it + 7).toByte() }

/** T019 (gateway-browser-session.yaml "Anonymous cart"): the cart cookie against a WireMock cart service. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class CartCookieIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private val upstream = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("cart-key-1")
        private val sealer = AesGcmSessionSealer(mapOf("k1" to SecretKeySpec(KEY_BYTES, "AES")), "k1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            listOf("IDENTITY_URL", "CART_URL").forEach { registry.add(it) { upstream.baseUrl() } }
            registry.add("JWKS_URI") { upstream.baseUrl() + JWKS_PATH }
            registry.add("BROWSER_SESSION_KEY") { Base64.getEncoder().encodeToString(KEY_BYTES) }
        }

        @JvmStatic
        @AfterAll
        fun stopUpstream() = upstream.stop()
    }

    @BeforeEach
    fun stubCart() {
        upstream.resetAll()
        upstream.stubFor(get(JWKS_PATH).willReturn(okJson(TestSigningKey.jwks(signingKey))))
        upstream.stubFor(get(CART).willReturn(okJson("""{"lines":[]}""")))
        upstream.stubFor(
            post(LINES).willReturn(
                aResponse()
                    .withStatus(HttpStatus.CREATED.value())
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .withHeader(CART_TOKEN, UPSTREAM_TOKEN)
                    .withBody("""{"lines":[{"productId":"$PRODUCT_ID"}]}"""),
            ),
        )
        upstream.stubFor(post(MERGE).willReturn(okJson("""{"mergedLines":1}""")))
    }

    private fun sealedCart(token: String = UPSTREAM_TOKEN) = sealer.sealToken(token)

    private fun sessionCookie(): String {
        val now = Instant.now()
        return sealer.seal(SealedSession(signingKey.token(), "refresh-1", SHOPPER_ID, setOf("shopper"), now, now))
    }

    private fun cartCookieHeader(headers: HttpHeaders): String? =
        headers[HttpHeaders.SET_COOKIE].orEmpty().firstOrNull { it.startsWith("$CART_COOKIE=") }

    @Test
    fun `an upstream X-Cart-Token becomes the sealed cart cookie and leaves the response`() {
        val headers =
            client
                .post()
                .uri(LINES)
                .header(BROWSER_SESSION, "cookie")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"productId":"$PRODUCT_ID","quantity":1}""")
                .exchange()
                .expectStatus()
                .isCreated
                .expectHeader()
                .doesNotExist(CART_TOKEN)
                .returnResult(String::class.java)
                .responseHeaders
        val cookie = cartCookieHeader(headers).shouldNotBeNull()
        cookie shouldStartWith "$CART_COOKIE=k1."
        cookie shouldEndWith "; $CART_ATTRIBUTES"
        sealer.unsealToken(cookie.removePrefix("$CART_COOKIE=").substringBefore(';')) shouldBe UPSTREAM_TOKEN
        headers[HttpHeaders.SET_COOKIE].orEmpty().last() shouldBe
            "$HOST_CART_COOKIE=; Max-Age=0; HttpOnly; Secure; SameSite=Lax; Path=/"
    }

    @Test
    fun `the cart cookie is injected as X-Cart-Token upstream, an explicit header wins`() {
        client
            .get()
            .uri(CART)
            .header(BROWSER_SESSION, "cookie")
            .cookie(CART_COOKIE, sealedCart())
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .doesNotExist(HttpHeaders.SET_COOKIE)
        upstream.verify(getRequestedFor(urlPathEqualTo(CART)).withHeader(CART_TOKEN, equalTo(UPSTREAM_TOKEN)))

        client
            .get()
            .uri(CART)
            .header(BROWSER_SESSION, "cookie")
            .header(CART_TOKEN, "explicit")
            .cookie(CART_COOKIE, sealedCart())
            .exchange()
            .expectStatus()
            .isOk
        upstream.verify(getRequestedFor(urlPathEqualTo(CART)).withHeader(CART_TOKEN, equalTo("explicit")))

        client
            .get()
            .uri(CART)
            .header(BROWSER_SESSION, "cookie")
            .cookie(CART_COOKIE, "k1.tampered")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.SET_COOKIE, CART_DELETION)
        upstream.findAll(getRequestedFor(urlPathEqualTo(CART)).withHeader(CART_TOKEN, absent())).size shouldBe 1
    }

    @Test
    fun `a merge that succeeds deletes the cart cookie, a 409 keeps it`() {
        client
            .post()
            .uri(MERGE)
            .header(BROWSER_SESSION, "cookie")
            .header("Sec-Fetch-Site", "same-origin")
            .cookie("session", sessionCookie())
            .cookie(CART_COOKIE, sealedCart())
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"mergedLines":1}""")
        val merged = upstream.findAll(postRequestedFor(urlPathEqualTo(MERGE))).single()
        merged.getHeader(CART_TOKEN) shouldBe UPSTREAM_TOKEN
        merged.getHeader(HttpHeaders.AUTHORIZATION).shouldNotBeNull() shouldStartWith "Bearer "
        merged.getHeader("X-Account-Id") shouldBe SHOPPER_ID

        upstream.stubFor(post(MERGE).willReturn(aResponse().withStatus(HttpStatus.CONFLICT.value())))
        val kept =
            client
                .post()
                .uri(MERGE)
                .header(BROWSER_SESSION, "cookie")
                .header("Sec-Fetch-Site", "same-origin")
                .cookie("session", sessionCookie())
                .cookie(CART_COOKIE, sealedCart())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .returnResult(String::class.java)
                .responseHeaders
        kept[HttpHeaders.SET_COOKIE].orEmpty().filter { it.startsWith("$CART_COOKIE=") }.shouldBe(emptyList())
    }

    @Test
    fun `the merge response carries the cart deletion`() {
        val headers =
            client
                .post()
                .uri(MERGE)
                .header(BROWSER_SESSION, "cookie")
                .header("Sec-Fetch-Site", "same-origin")
                .cookie("session", sessionCookie())
                .cookie(CART_COOKIE, sealedCart())
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseHeaders
        cartCookieHeader(headers) shouldBe CART_DELETION
    }

    @Test
    fun `without the browser header nothing changes, the token header stays visible and no cookie is read`() {
        client
            .post()
            .uri(LINES)
            .contentType(MediaType.APPLICATION_JSON)
            .cookie(CART_COOKIE, sealedCart("ignored"))
            .bodyValue("""{"productId":"$PRODUCT_ID","quantity":1}""")
            .exchange()
            .expectStatus()
            .isCreated
            .expectHeader()
            .valueEquals(CART_TOKEN, UPSTREAM_TOKEN)
            .expectHeader()
            .doesNotExist(HttpHeaders.SET_COOKIE)
        upstream.verify(postRequestedFor(urlPathEqualTo(LINES)).withHeader(CART_TOKEN, absent()))
        cartCookieHeader(HttpHeaders()).shouldBeNull()
    }
}

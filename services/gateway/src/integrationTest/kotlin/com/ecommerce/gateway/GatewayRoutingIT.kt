package com.ecommerce.gateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val JWKS_PATH = "/.well-known/jwks.json"
private const val BEARER = "Bearer "
private const val PRODUCTS_BODY = """{"items":[{"id":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01"}],"page":0}"""
private const val AUTH_TIER_LIMIT = 10
private const val ONE_MIB = 1024 * 1024
private const val UPSTREAM_DELAY_MS = 6000
private const val SECONDS_PER_MINUTE = 60L
private const val ORDER_ID = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"
private const val TRACEPARENT = "traceparent"
private const val ADDRESSES = "/api/v1/identity/accounts/me/addresses"

/**
 * The gateway in front of WireMock upstreams (identity, catalog, cart, order and notification share one WireMock;
 * payment points at a closed port) with a WireMock-served JWKS holding a key generated for the run.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("TooManyFunctions") // one function per gateway behaviour of gateway-routes.md
class GatewayRoutingIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private const val LOG_POLL_ATTEMPTS = 50
        private const val LOG_POLL_INTERVAL_MS = 100L
        private val upstream = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("it-key-1")
        private val otherKey = TestSigningKey("it-key-1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            listOf("IDENTITY_URL", "CATALOG_URL", "CART_URL", "ORDER_URL", "NOTIFICATION_URL").forEach { name ->
                registry.add(name) { upstream.baseUrl() }
            }
            registry.add("PAYMENT_URL") { "http://127.0.0.1:${unusedPort()}" }
            registry.add("JWKS_URI") { upstream.baseUrl() + JWKS_PATH }
        }

        @JvmStatic
        @AfterAll
        fun stopUpstream() = upstream.stop()
    }

    @BeforeEach
    fun stubUpstreams() {
        upstream.resetAll()
        upstream.stubFor(get(JWKS_PATH).willReturn(okJson(TestSigningKey.jwks(signingKey))))
        upstream.stubFor(
            get(urlPathEqualTo("/api/v1/catalog/products")).willReturn(
                okJson(PRODUCTS_BODY)
                    .withHeader("Server", "Jetty(12)")
                    .withHeader("X-Internal-Token", "leak")
                    .withHeader(HttpHeaders.CACHE_CONTROL, "max-age=60"),
            ),
        )
        upstream.stubFor(get("/api/v1/cart").willReturn(okJson("""{"lines":[]}""")))
        upstream.stubFor(
            get(
                "/api/v1/orders",
            ).willReturn(okJson("""{"items":[]}""").withHeader(HttpHeaders.CACHE_CONTROL, "max-age=60")),
        )
        upstream.stubFor(post("/api/v1/identity/sessions").willReturn(okJson("""{"accessToken":"t"}""")))
        upstream.stubFor(post("/api/v1/cart/lines").willReturn(aResponse().withStatus(HttpStatus.CREATED.value())))
        upstream.stubFor(
            post(
                "/api/v1/catalog/products/$ORDER_ID/images",
            ).willReturn(aResponse().withStatus(HttpStatus.CREATED.value())),
        )
        upstream.stubFor(
            get(
                "/api/v1/catalog/categories",
            ).willReturn(aResponse().withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())),
        )
        upstream.stubFor(
            get("/api/v1/catalog/categories/$ORDER_ID").willReturn(okJson("{}").withFixedDelay(UPSTREAM_DELAY_MS)),
        )
    }

    @Test
    fun `an unknown route answers 404 problem with the correlation id and forwards nothing`() {
        client
            .get()
            .uri("/api/v2/whatever")
            .exchange()
            .expectProblem(HttpStatus.NOT_FOUND, "not-found")
        client
            .delete()
            .uri(
                "/api/v1/catalog/products/$ORDER_ID",
            ).exchange()
            .expectProblem(HttpStatus.NOT_FOUND, "not-found")

        upstream.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0
    }

    @Test
    fun `internal, JWKS and actuator paths are not routed`() {
        listOf(
            "/internal/x",
            "/internal/accounts/$ORDER_ID/contact",
            JWKS_PATH,
            "/actuator/health",
            "/actuator/prometheus",
        ).forEach { path ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
        }

        upstream.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0
    }

    @Test
    fun `a protected prefix without a token answers 401 problem`() {
        val problem =
            client
                .get()
                .uri(
                    "/api/v1/orders",
                ).exchange()
                .expectProblem(HttpStatus.UNAUTHORIZED, "unauthorized")

        problem.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
        problem.headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        upstream.findAll(getRequestedFor(urlPathEqualTo("/api/v1/orders"))) shouldHaveSize 0
    }

    @Test
    fun `expired, wrong-audience, wrong-issuer, unknown-key and forged tokens answer 401`() {
        val invalid =
            listOf(
                signingKey.token(issuedAt = Instant.now().minus(Duration.ofHours(1))),
                signingKey.token { audience("another-api") },
                signingKey.token { issuer("https://evil.example") },
                signingKey.token(headerKid = "unknown-kid"),
                otherKey.token(),
                "not-a-jwt",
            )
        invalid.forEach { token ->
            client
                .get()
                .uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, BEARER + token)
                .exchange()
                .expectProblem(HttpStatus.UNAUTHORIZED, "unauthorized")
        }
        upstream.findAll(getRequestedFor(urlPathEqualTo("/api/v1/orders"))) shouldHaveSize 0
    }

    @Test
    fun `a present but invalid token on an anonymous route still answers 401`() {
        client
            .get()
            .uri("/api/v1/catalog/products")
            .header(HttpHeaders.AUTHORIZATION, BEARER + signingKey.token { audience("another-api") })
            .exchange()
            .expectProblem(HttpStatus.UNAUTHORIZED, "unauthorized")
    }

    @Test
    fun `anonymous catalogue and cart reads pass through with the upstream response`() {
        client
            .get()
            .uri("/api/v1/catalog/products?page=0")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=60")
            .expectBody()
            .json(PRODUCTS_BODY)
        client
            .get()
            .uri("/api/v1/cart")
            .header("X-Cart-Token", "cart-token-1")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"lines":[]}""")

        upstream.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/cart")).withHeader("X-Cart-Token", equalTo("cart-token-1")),
        )
        upstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/cart")).withHeader("X-Account-Id", absent()))
    }

    @Test
    fun `an operator route with a shopper token answers 403 problem`() {
        client
            .post()
            .uri("/api/v1/orders/$ORDER_ID/status")
            .header(HttpHeaders.AUTHORIZATION, BEARER + signingKey.token())
            .bodyValue(mapOf("orderStatus" to "preparing"))
            .exchange()
            .expectProblem(HttpStatus.FORBIDDEN, "forbidden")

        upstream.findAll(anyRequestedFor(urlPathEqualTo("/api/v1/orders/$ORDER_ID/status"))) shouldHaveSize 0
    }

    @Test
    fun `a valid shopper token is forwarded with the gateway's identity headers only`() {
        val token = signingKey.token(roles = listOf("shopper"))
        client
            .get()
            .uri("/api/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, BEARER + token)
            .header("X-Account-Id", "9e8d7c6b-5a49-4382-9f1e-0d2c4b6a8e10")
            .header("X-Roles", "operator")
            .header("X-Internal-Token", "guessed")
            .header("X-Forwarded-For", "203.0.113.7")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")

        val forwarded = upstream.findAll(getRequestedFor(urlPathEqualTo("/api/v1/orders"))).single()
        forwarded.header("X-Account-Id").values() shouldBe listOf(SHOPPER_ID)
        forwarded.header("X-Roles").values() shouldBe listOf("shopper")
        forwarded.containsHeader("X-Internal-Token") shouldBe false
        forwarded.containsHeader("X-Forwarded-For") shouldBe false
        forwarded.getHeader(HttpHeaders.AUTHORIZATION) shouldBe BEARER + token
    }

    @Test
    fun `a malformed correlation id is replaced, echoed, forwarded and logged as originalCorrelationId`(
        output: CapturedOutput,
    ) {
        val replaced =
            client
                .get()
                .uri("/api/v1/catalog/products")
                .header(CORRELATION, "abc-123")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseHeaders
                .getFirst(CORRELATION)
                .shouldNotBeNull()

        replaced shouldNotBe "abc-123"
        UUID.fromString(replaced).toString() shouldBe replaced
        upstream.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/catalog/products")).withHeader(CORRELATION, equalTo(replaced)),
        )
        // The access line is written after the response has been sent; give it a moment under load.
        accessLineWith(output, "\"originalCorrelationId\":\"abc-123\"") shouldContain
            "\"correlationId\":\"$replaced\""
    }

    @Test
    fun `the access line carries the trace and span of the request`(output: CapturedOutput) {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val clientSpanId = "00f067aa0ba902b7"
        client
            .get()
            .uri("/api/v1/catalog/products")
            .header(TRACEPARENT, "00-$traceId-$clientSpanId-01")
            .exchange()
            .expectStatus()
            .isOk
        val forwarded =
            upstream
                .findAll(getRequestedFor(urlPathEqualTo("/api/v1/catalog/products")))
                .single()
                .getHeader(TRACEPARENT)

        // The gateway's server span continues the client's trace; its id is in the access line, and the upstream
        // call is a child of the same trace.
        val line = accessLineWith(output, "\"durationMs\"", "\"traceId\":\"$traceId\"")
        val spanId = Regex("\"spanId\":\"([0-9a-f]{16})\"").find(line).shouldNotBeNull().groupValues[1]
        spanId shouldNotBe clientSpanId
        forwarded shouldContain traceId
    }

    private fun accessLineWith(
        output: CapturedOutput,
        vararg markers: String,
    ): String {
        fun matches(candidate: String) = markers.all(candidate::contains)
        repeat(LOG_POLL_ATTEMPTS) {
            val line =
                output.out
                    .lines()
                    .singleOrNull(::matches)
            if (line != null) return line
            Thread.sleep(LOG_POLL_INTERVAL_MS)
        }
        return output.out.lines().single(::matches)
    }

    @Test
    fun `a valid correlation id is preserved, forwarded and echoed on gateway errors too`() {
        val valid = "checkout-flow-0001-a"
        client
            .get()
            .uri("/api/v1/catalog/products")
            .header(CORRELATION, valid)
            .exchange()
            .expectHeader()
            .valueEquals(CORRELATION, valid)
        upstream.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/catalog/products")).withHeader(CORRELATION, equalTo(valid)),
        )

        val problem =
            client
                .get()
                .uri("/nowhere")
                .header(CORRELATION, valid)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
        problem.correlationHeader shouldBe valid
    }

    @Test
    fun `the auth tier answers 429 problem with Retry-After after 10 requests per minute`() {
        repeat(AUTH_TIER_LIMIT) {
            client
                .post()
                .uri(
                    "/api/v1/identity/sessions",
                ).bodyValue(mapOf("email" to "a@b.c"))
                .exchange()
                .expectStatus()
                .isOk
        }
        val problem =
            client
                .post()
                .uri("/api/v1/identity/sessions")
                .bodyValue(mapOf("email" to "a@b.c"))
                .exchange()
                .expectProblem(HttpStatus.TOO_MANY_REQUESTS, "throttled")

        problem.headers
            .getFirst(HttpHeaders.RETRY_AFTER)
            .shouldNotBeNull()
            .toLong() shouldBeInRange 1L..SECONDS_PER_MINUTE
        upstream.findAll(anyRequestedFor(urlPathEqualTo("/api/v1/identity/sessions"))) shouldHaveSize AUTH_TIER_LIMIT
    }

    @Test
    fun `a body over 1 MiB answers 413 problem, the image route accepts up to 5 MiB`() {
        client
            .post()
            .uri("/api/v1/cart/lines")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(ByteArray(ONE_MIB + 1))
            .exchange()
            .expectProblem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large")
        upstream.findAll(anyRequestedFor(urlPathEqualTo("/api/v1/cart/lines"))) shouldHaveSize 0

        client
            .post()
            .uri("/api/v1/cart/lines")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(ByteArray(ONE_MIB))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/v1/catalog/products/$ORDER_ID/images")
            .header(
                HttpHeaders.AUTHORIZATION,
                BEARER + signingKey.token(subject = OPERATOR_ID, roles = listOf("operator")),
            ).header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(ByteArray(2 * ONE_MIB))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `security headers are on proxied responses and on gateway errors, server details are removed`() {
        val proxied =
            client
                .get()
                .uri("/api/v1/catalog/products")
                .exchange()
                .returnResult(String::class.java)
                .responseHeaders
        val error =
            client
                .get()
                .uri("/nowhere")
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
                .headers

        listOf(proxied, error).forEach { headers ->
            headers.getFirst("Strict-Transport-Security") shouldBe "max-age=31536000; includeSubDomains"
            headers.getFirst("X-Content-Type-Options") shouldBe "nosniff"
            headers.getFirst("X-Frame-Options") shouldBe "DENY"
            headers.getFirst("Content-Security-Policy").shouldNotBeNull()
            headers.getFirst("Server").shouldBeNull()
            headers.getFirst("X-Internal-Token").shouldBeNull()
        }
    }

    @Test
    fun `upstream errors pass through and are not retried, failures to connect answer 503 problem`() {
        client
            .get()
            .uri(
                "/api/v1/catalog/categories",
            ).exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        upstream.findAll(getRequestedFor(urlPathEqualTo("/api/v1/catalog/categories"))) shouldHaveSize 1

        // An identity POST is retried only when no connection could be opened (GatewayRetryIT), never on an answer;
        // a standard-tier route, so that the auth tier's budget stays whole for its own test.
        upstream.stubFor(post(ADDRESSES).willReturn(aResponse().withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))
        client
            .post()
            .uri(ADDRESSES)
            .header(HttpHeaders.AUTHORIZATION, BEARER + signingKey.token())
            .bodyValue(mapOf("label" to "Home"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        upstream.findAll(postRequestedFor(urlPathEqualTo(ADDRESSES))) shouldHaveSize 1

        client
            .get()
            .uri("/api/v1/payments/attempts?orderId=$ORDER_ID")
            .header(HttpHeaders.AUTHORIZATION, BEARER + signingKey.token())
            .exchange()
            .expectProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable")
    }

    @Test
    fun `an upstream slower than its tier timeout answers 504 problem`() {
        client
            .get()
            .uri(
                "/api/v1/catalog/categories/$ORDER_ID",
            ).exchange()
            .expectProblem(HttpStatus.GATEWAY_TIMEOUT, "unavailable")
    }
}

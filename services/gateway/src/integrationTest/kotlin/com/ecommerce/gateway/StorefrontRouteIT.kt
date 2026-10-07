package com.ecommerce.gateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

private const val JWKS_PATH = "/.well-known/jwks.json"
private const val PRODUCT_PATH = "/products/0b4e6d1c-7a2f-4e39-9b61-5d8c2f0a1e77"
private const val ASSET_PATH = "/assets/app-3f9c.js"
private const val SHELL = "<!doctype html><html><body><div id=\"root\"></div></body></html>"
private const val IMMUTABLE = "public, max-age=31536000, immutable"
private const val TRACES = "/api/v1/telemetry/v1/traces"
private const val LOGS = "/api/v1/telemetry/v1/logs"
private const val TELEMETRY_LIMIT = 256 * 1024
private const val STOREFRONT_CSP =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; " +
        "font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"

/**
 * T022 (feature 005 gateway-routes.md): the storefront catch-all and the telemetry routes against WireMock standing
 * in for the static container and the collector; the identity upstreams point at the same WireMock (JWKS).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class StorefrontRouteIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private val upstream = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("storefront-key-1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            listOf(
                "IDENTITY_URL",
                "STOREFRONT_URL",
                "OTEL_COLLECTOR_URL",
            ).forEach { registry.add(it) { upstream.baseUrl() } }
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
        listOf("/", PRODUCT_PATH, "/internal/x").forEach { path ->
            upstream.stubFor(
                any(urlPathEqualTo(path)).willReturn(
                    aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                        .withHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                        .withHeader("Server", "nginx")
                        .withBody(SHELL),
                ),
            )
        }
        upstream.stubFor(
            get(ASSET_PATH).willReturn(
                aResponse()
                    .withStatus(HttpStatus.OK.value())
                    .withHeader(HttpHeaders.CONTENT_TYPE, "text/javascript")
                    .withHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE)
                    .withBody("console.log('storefront')"),
            ),
        )
        upstream.stubFor(post("/v1/traces").willReturn(okJson("{}")))
        upstream.stubFor(post("/v1/logs").willReturn(okJson("""{"partialSuccess":{"rejectedLogRecords":0}}""")))
    }

    @Test
    fun `the shell and product pages come from the storefront upstream with the page headers and no-store`() {
        listOf("/", PRODUCT_PATH).forEach { path ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentType(MediaType.TEXT_HTML)
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader()
                .valueEquals("Content-Security-Policy", STOREFRONT_CSP)
                .expectHeader()
                .valueEquals("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
                .expectHeader()
                .valueEquals("X-Frame-Options", "DENY")
                .expectHeader()
                .valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .doesNotExist("Server")
                .expectBody(String::class.java)
                .isEqualTo(SHELL)
        }
        client
            .head()
            .uri("/")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `hashed assets keep the upstream immutable cache header`() {
        client
            .get()
            .uri(ASSET_PATH)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, IMMUTABLE)
            .expectHeader()
            .valueEquals("Content-Security-Policy", STOREFRONT_CSP)
    }

    @Test
    fun `a session cookie on the storefront route is neither read nor re-set`() {
        client
            .get()
            .uri("/")
            .cookie("session", "k1.not-a-sealed-value")
            .cookie("cart", "k1.not-a-sealed-value")
            .header("X-Browser-Session", "cookie")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .doesNotExist(HttpHeaders.SET_COOKIE)
        upstream.verify(getRequestedFor(urlPathEqualTo("/")).withHeader(HttpHeaders.AUTHORIZATION, absent()))
    }

    @Test
    fun `non-GET requests outside the API and GETs under the excluded prefixes stay 404 problems`() {
        listOf("/", "/products/1").forEach { path ->
            client
                .post()
                .uri(path)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
        }
        listOf("/api/v1/unknown", "/api", "/actuator/health", "/.well-known/jwks.json").forEach { path ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
                .headers
                .getFirst("Content-Security-Policy") shouldBe
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        }
        upstream.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0
    }

    @Test
    fun `API responses keep the strict API policy`() {
        client
            .post()
            .uri(TRACES)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"resourceSpans":[]}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(
                "Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            ).expectHeader()
            .doesNotExist("Permissions-Policy")
    }

    @Test
    fun `telemetry is forwarded to the collector's receiver without cookies or tokens, POST only, 256 KiB at most`() {
        client
            .post()
            .uri(TRACES)
            .cookie("session", "k1.whatever")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + signingKey.token())
            .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"resourceSpans":[]}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("{}")
        val traces = upstream.findAll(postRequestedFor(urlPathEqualTo("/v1/traces"))).single()
        traces.getHeader(HttpHeaders.COOKIE).shouldBeNull()
        traces.getHeader(HttpHeaders.AUTHORIZATION).shouldBeNull()
        // The upstream call is a child span of the browser's trace: same trace id, the gateway's own span id.
        traces.getHeader("traceparent") shouldStartWith "00-4bf92f3577b34da6a3ce929d0e0e4736-"

        client
            .post()
            .uri(LOGS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"resourceLogs":[]}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"partialSuccess":{"rejectedLogRecords":0}}""")
        upstream.findAll(postRequestedFor(urlPathEqualTo("/v1/logs"))) shouldHaveSize 1
    }

    @Test
    fun `telemetry refuses other methods with 404 and bodies over 256 KiB with 413 before forwarding`() {
        client
            .get()
            .uri(TRACES)
            .exchange()
            .expectProblem(HttpStatus.NOT_FOUND, "not-found")
        client
            .post()
            .uri(TRACES)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ByteArray(TELEMETRY_LIMIT + 1))
            .exchange()
            .expectProblem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large")
        upstream.findAll(postRequestedFor(urlPathEqualTo("/v1/traces"))) shouldHaveSize 0
    }
}

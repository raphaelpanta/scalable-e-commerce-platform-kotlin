package com.ecommerce.gateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.ServerSocket

private const val JWKS_PATH = "/.well-known/jwks.json"
private const val TRACES = "/api/v1/telemetry/v1/traces"
private const val LOGS = "/api/v1/telemetry/v1/logs"
private const val COLLECTOR_TRACES = "/v1/traces"
private const val COLLECTOR_LOGS = "/v1/logs"
private const val TRACES_BODY = """{"resourceSpans":[]}"""
private const val LOGS_BODY = """{"resourceLogs":[]}"""
private const val PARTIAL_SUCCESS = """{"partialSuccess":{"rejectedLogRecords":0}}"""
private const val REJECTED = """{"code":3,"message":"unknown field"}"""
private const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
private const val CORRELATION_ID = "3f1c9d52-8a14-4be0-b7f6-0e2d5a9c4c18"
private const val BODY_LIMIT_BYTES = 256 * 1024
private const val OVER_LIMIT_BYTES = 300 * 1024

/** A small `browse` budget so that exhausting it takes a handful of requests; a token returns every 3 seconds. */
private const val BROWSE_PER_MINUTE = 20
private const val EXHAUST_ATTEMPTS = BROWSE_PER_MINUTE * 2
private const val LAST = Int.MAX_VALUE

// Test order: the throttling test empties the shared bucket and must run last.
private const val FORWARDED = 1
private const val PASSED_THROUGH = 2
private const val COLLECTOR_REJECTS = 3
private const val BODY_LIMIT = 4
private const val COLLECTOR_DOWN = 5
private const val OTHER_METHODS = 6
private const val RETRY_AFTER_MAX_SECONDS = 60L

/**
 * T091 (feature 005 telemetry.yaml, gateway-routes.md): the telemetry routes against a WireMock collector. Complements
 * [StorefrontRouteIT], which covers the happy path next to the storefront catch-all: this class pins the rewritten
 * paths, the stripped credentials, what is passed through from the collector, and every drop rule of FR-032 (413, 429,
 * 503, 404). The throttling test runs last (`@Order`): it empties the shared `browse` bucket of the test client.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["gateway.rate-limit.requests-per-minute.browse=$BROWSE_PER_MINUTE"],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TelemetryRouteIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private val collectorPort: Int = ServerSocket(0).use { it.localPort }
        private val collector = WireMockServer(wireMockConfig().port(collectorPort)).apply { start() }
        private val identity = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("telemetry-key-1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            registry.add("OTEL_COLLECTOR_URL") { "http://localhost:$collectorPort" }
            registry.add("JWKS_URI") { identity.baseUrl() + JWKS_PATH }
        }

        @JvmStatic
        @AfterAll
        fun stopUpstreams() {
            collector.stop()
            identity.stop()
        }
    }

    @BeforeEach
    fun stubUpstreams() {
        identity.resetAll()
        identity.stubFor(get(JWKS_PATH).willReturn(okJson(TestSigningKey.jwks(signingKey))))
        if (!collector.isRunning) collector.start()
        collector.resetAll()
        collector.stubFor(post(COLLECTOR_TRACES).willReturn(okJson("{}")))
        collector.stubFor(post(COLLECTOR_LOGS).willReturn(okJson(PARTIAL_SUCCESS)))
    }

    private fun export(
        path: String,
        body: Any = TRACES_BODY,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    @Test
    @Order(FORWARDED)
    fun `traces and logs reach the collector under the rewritten path with no cookie and no authorization`() {
        mapOf(TRACES to COLLECTOR_TRACES, LOGS to COLLECTOR_LOGS).forEach { (path, rewritten) ->
            client
                .post()
                .uri(path)
                .cookie("session", "k1.sealed-value")
                .cookie("cart", "k1.sealed-value")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${signingKey.token()}")
                .header(CORRELATION, CORRELATION_ID)
                .header("traceparent", TRACEPARENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(TRACES_BODY)
                .exchange()
                .expectStatus()
                .isOk

            val forwarded = collector.findAll(postRequestedFor(urlPathEqualTo(rewritten))).single()
            forwarded.getHeader(HttpHeaders.COOKIE).shouldBeNull()
            forwarded.getHeader(HttpHeaders.AUTHORIZATION).shouldBeNull()
            forwarded.getHeader(HttpHeaders.CONTENT_TYPE) shouldBe MediaType.APPLICATION_JSON_VALUE
            forwarded.getHeader(CORRELATION) shouldBe CORRELATION_ID
            forwarded.bodyAsString shouldBe TRACES_BODY
        }
    }

    @Test
    @Order(PASSED_THROUGH)
    fun `the collector's 200 body is passed through for traces and for logs`() {
        export(TRACES)
            .expectStatus()
            .isOk
            .expectBody()
            .json("{}")
        export(LOGS, LOGS_BODY)
            .expectStatus()
            .isOk
            .expectBody()
            .json(PARTIAL_SUCCESS)
    }

    @Test
    @Order(COLLECTOR_REJECTS)
    fun `a collector 400 is passed through with the collector's own body`() {
        collector.stubFor(
            post(COLLECTOR_TRACES).willReturn(
                aResponse()
                    .withStatus(HttpStatus.BAD_REQUEST.value())
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .withBody(REJECTED),
            ),
        )
        export(TRACES)
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .json(REJECTED)
        collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_TRACES))) shouldHaveSize 1
    }

    @Test
    @Order(BODY_LIMIT)
    fun `a body of exactly 256 KiB is accepted, one above it answers 413 payload-too-large before forwarding`() {
        export(LOGS, ByteArray(BODY_LIMIT_BYTES)).expectStatus().isOk
        collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_LOGS))) shouldHaveSize 1

        listOf(TRACES, LOGS).forEach { path ->
            export(path, ByteArray(OVER_LIMIT_BYTES)).expectProblem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large")
        }
        collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_TRACES))) shouldHaveSize 0
        collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_LOGS))) shouldHaveSize 1
    }

    @Test
    @Order(COLLECTOR_DOWN)
    fun `a collector that is not running answers 503 unavailable without a retry`() {
        collector.stop()
        val problem = export(TRACES).expectProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable")
        problem.headers.getFirst(HttpHeaders.SET_COOKIE).shouldBeNull()
        collector.start()
        collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_TRACES))) shouldHaveSize 0
    }

    @Test
    @Order(OTHER_METHODS)
    fun `any other method on a telemetry path is no route and answers 404 not-found`() {
        listOf(TRACES, LOGS).forEach { path ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
            client
                .put()
                .uri(path)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
            client
                .delete()
                .uri(path)
                .exchange()
                .expectProblem(HttpStatus.NOT_FOUND, "not-found")
        }
        collector.allServeEvents shouldHaveSize 0
    }

    @Test
    @Order(LAST)
    fun `the browse tier answers 429 throttled with Retry-After once its budget is spent`() {
        val forwardedBeforeThrottle = collectUntilThrottled()
        val problem = export(TRACES).expectProblem(HttpStatus.TOO_MANY_REQUESTS, "throttled")
        problem.headers
            .getFirst(HttpHeaders.RETRY_AFTER)
            .shouldNotBeNull()
            .toLong() shouldBeInRange 1L..RETRY_AFTER_MAX_SECONDS
        collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_TRACES))) shouldHaveSize forwardedBeforeThrottle
    }

    /** Sends exports until the first 429 and returns how many reached the collector. */
    private fun collectUntilThrottled(): Int {
        repeat(EXHAUST_ATTEMPTS) {
            val status = export(TRACES).returnResult(String::class.java).status
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                return collector.findAll(postRequestedFor(urlPathEqualTo(COLLECTOR_TRACES))).size
            }
        }
        error("the browse budget could not be exhausted")
    }
}

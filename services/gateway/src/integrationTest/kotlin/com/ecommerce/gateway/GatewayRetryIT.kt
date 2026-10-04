package com.ecommerce.gateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.NettyRoutingFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val JWKS = "/.well-known/jwks.json"

/** One attempt plus the two retries of the `Retry` filters of application.yml. */
private const val RETRIED_ATTEMPTS = 3

/**
 * T180 (FR-024, SC-008): when no connection to the upstream can be opened (nothing was sent), the gateway retries
 * reads and the identity POSTs, but never `POST /api/v1/orders`. Every upstream points at a closed port; a global
 * filter placed just before the forwarding filter counts the attempts of each route, so a retry is visible even
 * though no upstream ever receives a request.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(GatewayRetryIT.CountingConfiguration::class)
class GatewayRetryIT(
    @LocalServerPort private val port: Int,
    @Autowired private val attempts: AttemptCounter,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private val jwks = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("retry-key-1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            val refused = "http://127.0.0.1:${unusedPort()}"
            listOf("IDENTITY_URL", "CATALOG_URL", "CART_URL", "ORDER_URL", "PAYMENT_URL", "NOTIFICATION_URL").forEach {
                registry.add(it) { refused }
            }
            registry.add("JWKS_URI") { jwks.baseUrl() + JWKS }
        }

        @JvmStatic
        @AfterAll
        fun stopJwks() = jwks.stop()
    }

    @BeforeEach
    fun reset() {
        jwks.resetAll()
        jwks.stubFor(get(JWKS).willReturn(okJson(TestSigningKey.jwks(signingKey))))
        attempts.clear()
    }

    @Test
    fun `a sign-in whose upstream refuses the connection is retried, then answers 503 problem`() {
        client
            .post()
            .uri("/api/v1/identity/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"email":"nobody@example.com","password":"wrong-password"}""")
            .exchange()
            .expectProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable")

        attempts.of("identity-credentials") shouldBe RETRIED_ATTEMPTS
    }

    @Test
    fun `a checkout whose upstream refuses the connection is not retried`() {
        client
            .post()
            .uri("/api/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + signingKey.token())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"cartRevision":1}""")
            .exchange()
            .expectProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable")

        attempts.of("order-placement") shouldBe 1
    }

    @Test
    fun `a read whose upstream refuses the connection is retried`() {
        client
            .get()
            .uri("/api/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + signingKey.token())
            .exchange()
            .expectProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable")

        attempts.of("order-history") shouldBe RETRIED_ATTEMPTS
    }

    /** Attempts per route id, counted by [CountingConfiguration]'s filter. */
    class AttemptCounter {
        private val counts = ConcurrentHashMap<String, AtomicInteger>()

        fun record(routeId: String) {
            counts.computeIfAbsent(routeId) { AtomicInteger() }.incrementAndGet()
        }

        fun of(routeId: String): Int = counts[routeId]?.get() ?: 0

        fun clear() = counts.clear()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CountingConfiguration {
        @Bean
        fun attemptCounter(): AttemptCounter = AttemptCounter()

        /** Runs once per attempt: after the `Retry` filters, just before the request is forwarded. */
        @Bean
        fun attemptCountingFilter(counter: AttemptCounter): GlobalFilter =
            object : GlobalFilter, Ordered {
                @Suppress("ForbiddenVoid") // GlobalFilter's signature
                override fun filter(
                    exchange: ServerWebExchange,
                    chain: GatewayFilterChain,
                ): Mono<Void> {
                    val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
                    route?.let { counter.record(it.id) }
                    return chain.filter(exchange)
                }

                override fun getOrder(): Int = NettyRoutingFilter.ORDER - 1
            }
    }
}

package com.ecommerce.gateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/** The identity JWKS cannot be reached and no key was ever cached: protected routes fail closed with 503. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class JwksUnavailableIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private val upstream = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("unreachable-key")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            listOf("CATALOG_URL", "ORDER_URL").forEach { name -> registry.add(name) { upstream.baseUrl() } }
            registry.add("JWKS_URI") { "http://127.0.0.1:${unusedPort()}/.well-known/jwks.json" }
        }

        @JvmStatic
        @AfterAll
        fun stopUpstream() = upstream.stop()
    }

    @Test
    fun `a protected route answers 503 problem and an anonymous one is still served`() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/catalog/products")).willReturn(okJson("""{"items":[]}""")))
        upstream.stubFor(get("/api/v1/orders").willReturn(okJson("""{"items":[]}""")))

        client
            .get()
            .uri("/api/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + signingKey.token())
            .exchange()
            .expectProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable")
        client
            .get()
            .uri("/api/v1/catalog/products")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"items":[]}""")

        upstream.findAll(getRequestedFor(urlPathEqualTo("/api/v1/orders"))) shouldHaveSize 0
    }
}

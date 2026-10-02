package com.ecommerce.gateway

import com.ecommerce.gateway.security.JwksClient
import com.ecommerce.gateway.security.JwksUnavailableException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import java.time.Duration

private const val PATH = "/.well-known/jwks.json"
private const val SERVICE_UNAVAILABLE = 503
private const val LOOKUPS = 3
private val CACHE_TTL: Duration = Duration.ofMinutes(5)
private val EXPIRY_WAIT: Duration = Duration.ofMillis(20)

/** Caching, refresh and failure behaviour of the JWKS client against a WireMock identity service. */
class JwksClientIT {
    private val first = TestSigningKey("2026-10-a1")
    private val second = TestSigningKey("2026-10-b2")

    companion object {
        private val identity = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

        @JvmStatic
        @AfterAll
        fun stopIdentity() = identity.stop()
    }

    @BeforeEach
    fun reset() = identity.resetAll()

    private fun client(
        cacheTtl: Duration = CACHE_TTL,
        cooldown: Duration = Duration.ZERO,
    ) = JwksClient(
        WebClient.create(),
        URI.create(identity.baseUrl() + PATH),
        cacheTtl,
        cooldown,
        Duration.ofSeconds(2),
    )

    private fun serve(vararg keys: TestSigningKey) {
        identity.stubFor(get(PATH).willReturn(okJson(TestSigningKey.jwks(*keys))))
    }

    private fun fetches() = identity.findAll(getRequestedFor(urlPathEqualTo(PATH))).size

    @Test
    fun `keys are fetched once and served from the cache`() {
        serve(first)
        val jwks = client()

        repeat(LOOKUPS) { _ ->
            jwks
                .keysFor(first.kid)
                .block()
                .shouldNotBeNull()
                .map { key -> key.keyID } shouldBe listOf(first.kid)
        }
        fetches() shouldBe 1
    }

    @Test
    fun `an unknown kid forces a refresh that finds the rotated key`() {
        serve(first)
        val jwks = client()
        jwks.keysFor(first.kid).block().shouldNotBeNull() shouldHaveSize 1

        serve(second, first)
        jwks
            .keysFor(second.kid)
            .block()
            .shouldNotBeNull()
            .map { it.keyID } shouldBe listOf(second.kid)
        fetches() shouldBe 2
    }

    @Test
    fun `forced refreshes are rate limited by the cooldown`() {
        serve(first)
        val jwks = client(cooldown = Duration.ofMinutes(1))
        jwks.keysFor(first.kid).block()

        jwks
            .keysFor("unknown-1")
            .block()
            .shouldNotBeNull()
            .shouldBeEmpty()
        jwks
            .keysFor("unknown-2")
            .block()
            .shouldNotBeNull()
            .shouldBeEmpty()
        fetches() shouldBe 2
    }

    @Test
    fun `cached keys keep validating while the key set cannot be fetched`() {
        serve(first)
        val jwks = client(cacheTtl = Duration.ofMillis(1))
        jwks.keysFor(first.kid).block()

        identity.stubFor(get(PATH).willReturn(aResponse().withStatus(SERVICE_UNAVAILABLE)))
        Thread.sleep(EXPIRY_WAIT)
        jwks
            .keysFor(first.kid)
            .block()
            .shouldNotBeNull()
            .map { it.keyID } shouldBe listOf(first.kid)
        shouldThrow<JwksUnavailableException> { jwks.keysFor(second.kid).block() }
    }

    @Test
    fun `without any fetched key set the lookup fails as unavailable`() {
        identity.stubFor(get(PATH).willReturn(aResponse().withStatus(SERVICE_UNAVAILABLE)))

        shouldThrow<JwksUnavailableException> { client().keysFor(first.kid).block() }
    }
}

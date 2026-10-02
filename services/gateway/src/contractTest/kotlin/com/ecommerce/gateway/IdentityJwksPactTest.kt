package com.ecommerce.gateway

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.matchingrules.MinTypeMatcher
import com.ecommerce.gateway.security.GatewayJwtDecoder
import com.ecommerce.gateway.security.JwksClient
import com.ecommerce.gateway.security.JwksUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import java.time.Duration

private const val PATH = "/.well-known/jwks.json"
private const val CORRELATION_HEADER = "X-Correlation-Id"
private const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
private const val CORRELATION_REGEX = "^[A-Za-z0-9-]{1,64}$"
private const val X_REGEX = "^[A-Za-z0-9_-]{43}$"
private const val OK = 200
private const val SERVICE_UNAVAILABLE = 503
private const val KID_A1 = "2026-10-a1"
private const val KID_B2 = "2026-10-b2"
private const val X_A1 = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"
private val CACHE_TTL: Duration = Duration.ofMinutes(5)
private val REFRESH_COOLDOWN: Duration = Duration.ofSeconds(10)
private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(5)
private const val X_B2 = "xTLzCYrKsV7o344bBsBdQz1P-GDGXTbhGCw2GTt2rEA"

/** One key of the expected JWK Set: `kty`, `crv`, `kid`, `use`, `alg` exact, `x` by regex. */
private data class ExpectedKey(
    val kid: String,
    val x: String,
)

/** `{"keys":[...]}` with at least [minimum] keys, the given ones first and in this order. */
private fun jwkSet(
    minimum: Int,
    vararg keys: ExpectedKey,
): PactDslJsonBody {
    val array = PactDslJsonBody().array("keys")
    keys.forEach { key ->
        array
            .`object`()
            .stringMatcher("kty", "^OKP$", "OKP")
            .stringMatcher("crv", "^Ed25519$", "Ed25519")
            .stringMatcher("kid", "^${key.kid}$", key.kid)
            .stringMatcher("x", X_REGEX, key.x)
            .stringMatcher("use", "^sig$", "sig")
            .stringMatcher("alg", "^EdDSA$", "EdDSA")
            .closeObject()
    }
    val body = array.closeArray() as PactDslJsonBody
    body.matchers.addRule("$.keys", MinTypeMatcher(minimum))
    return body
}

private fun PactDslWithState.jwksRequest(description: String) =
    uponReceiving(description)
        .path(PATH)
        .method("GET")
        .headers("Accept", "application/json")
        .matchHeader(CORRELATION_HEADER, CORRELATION_REGEX, CORRELATION_ID)

/**
 * Consumer side of `gateway` -> `identity` (pact-interactions.md section 2.1): the gateway's [JwksClient] and token
 * decoder read the JWK Set at `GET /.well-known/jwks.json`. The token used here is signed with the private key of
 * the first JWKS example (RFC 8037 appendix A), so the pact also pins the shared token contract (section 4).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "identity", pactVersion = PactSpecVersion.V4)
class IdentityJwksPactTest {
    @Pact(consumer = "gateway")
    fun activeKey(builder: PactDslWithProvider): V4Pact =
        builder
            .given("identity has an active signing key", mapOf("kid" to KID_A1))
            .jwksRequest("a request for the JWKS document")
            .willRespondWith()
            .status(OK)
            .matchHeader("Content-Type", "^application/json(;.*)?$", "application/json")
            .matchHeader(CORRELATION_HEADER, CORRELATION_REGEX, CORRELATION_ID)
            .body(jwkSet(1, ExpectedKey(KID_A1, X_A1)))
            .toPact(V4Pact::class.java)

    @Pact(consumer = "gateway")
    fun rotatedKey(builder: PactDslWithProvider): V4Pact =
        builder
            .given("identity has rotated its signing key", mapOf("previousKid" to KID_A1, "kid" to KID_B2))
            .jwksRequest("a request for the JWKS document after a key rotation")
            .willRespondWith()
            .status(OK)
            .matchHeader("Content-Type", "^application/json(;.*)?$", "application/json")
            .matchHeader(CORRELATION_HEADER, CORRELATION_REGEX, CORRELATION_ID)
            .body(jwkSet(2, ExpectedKey(KID_B2, X_B2), ExpectedKey(KID_A1, X_A1)))
            .toPact(V4Pact::class.java)

    @Pact(consumer = "gateway")
    fun noSigningKey(builder: PactDslWithProvider): V4Pact =
        builder
            .given("identity has no signing key available")
            .jwksRequest("a request for the JWKS document while no signing key is available")
            .willRespondWith()
            .status(SERVICE_UNAVAILABLE)
            .matchHeader("Content-Type", "^application/problem\\+json(;.*)?$", "application/problem+json")
            .matchHeader(CORRELATION_HEADER, CORRELATION_REGEX, CORRELATION_ID)
            .body(
                PactDslJsonBody()
                    .stringValue("type", "https://ecommerce.example/problems/unavailable")
                    .stringValue("title", "Service unavailable")
                    .numberValue("status", SERVICE_UNAVAILABLE)
                    .stringType("detail", "No signing key is available.")
                    .stringType("correlationId", CORRELATION_ID),
            ).toPact(V4Pact::class.java)

    private fun jwksClient(mockServer: MockServer) =
        JwksClient(
            WebClient.create(),
            URI.create(mockServer.getUrl() + PATH),
            CACHE_TTL,
            REFRESH_COOLDOWN,
            FETCH_TIMEOUT,
        )

    @Test
    @PactTestFor(pactMethod = "activeKey")
    fun `a token signed with the active key validates`(mockServer: MockServer) {
        val jwks = jwksClient(mockServer)
        val decoder = GatewayJwtDecoder.create(jwks, RfcKey.ISSUER, RfcKey.AUDIENCE)

        val jwt = decoder.decode(RfcKey.token(KID_A1)).block().shouldNotBeNull()

        jwt.subject shouldBe RfcKey.SUBJECT
        jwt.getClaimAsStringList("roles") shouldBe listOf("shopper")
        jwks
            .keysFor(KID_A1)
            .block()
            .shouldNotBeNull()
            .map { it.toJSONObject()["x"] } shouldBe listOf(X_A1)
    }

    @Test
    @PactTestFor(pactMethod = "rotatedKey")
    fun `after a rotation the new key comes first and tokens of the previous key still validate`(
        mockServer: MockServer,
    ) {
        val jwks = jwksClient(mockServer)
        val decoder = GatewayJwtDecoder.create(jwks, RfcKey.ISSUER, RfcKey.AUDIENCE)

        decoder
            .decode(RfcKey.token(KID_A1))
            .block()
            .shouldNotBeNull()
            .subject shouldBe RfcKey.SUBJECT
        jwks
            .keysFor(null)
            .block()
            .shouldNotBeNull()
            .map { it.keyID } shouldBe listOf(KID_B2, KID_A1)
    }

    @Test
    @PactTestFor(pactMethod = "noSigningKey")
    fun `without a signing key tokens are refused as unavailable, never accepted`(mockServer: MockServer) {
        val jwks = jwksClient(mockServer)
        val decoder = GatewayJwtDecoder.create(jwks, RfcKey.ISSUER, RfcKey.AUDIENCE)

        shouldThrow<JwksUnavailableException> { decoder.decode(RfcKey.token(KID_A1)).block() }
    }
}

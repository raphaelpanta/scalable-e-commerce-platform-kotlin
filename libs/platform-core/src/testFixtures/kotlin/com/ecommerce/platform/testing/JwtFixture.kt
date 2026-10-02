package com.ecommerce.platform.testing

import com.ecommerce.platform.security.Ed25519Jwks
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Issues EdDSA (Ed25519) access tokens shaped like the identity service's (service conventions section 3) and
 * serves the matching JWKS:
 *
 * ```kotlin
 * val jwt = JwtFixture().also { it.startJwks() }
 * // @DynamicPropertySource: registry.add("platform.security.jwks-uri") { jwt.jwksUri }
 * client.get().uri("/api/v1/orders").header("Authorization", "Bearer ${jwt.tokenFor(accountId, setOf("shopper"))}")
 * ```
 */
class JwtFixture(
    val issuer: String = DEFAULT_ISSUER,
    val audience: String = DEFAULT_AUDIENCE,
    val keyId: String = DEFAULT_KEY_ID,
) : AutoCloseable {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance(Ed25519Jwks.ALGORITHM).generateKeyPair()
    private var server: WireMockServer? = null

    /** The public JWKS document (`{"keys":[{"kty":"OKP","crv":"Ed25519",...}]}`). */
    fun jwksJson(): String = JWKSet(Ed25519Jwks.jwkOf(keyPair.public, keyId)).toString()

    /**
     * A signed access token for [accountId] with [roles] (`shopper`, `operator`), valid for [expiresIn] (negative
     * for an expired token); [audience] and [issuer] can be overridden to build invalid tokens.
     */
    @Suppress("LongParameterList") // every claim can be varied to build an invalid token
    fun tokenFor(
        accountId: UUID,
        roles: Collection<String> = setOf("shopper"),
        expiresIn: Duration = DEFAULT_LIFETIME,
        audience: String = this.audience,
        issuer: String = this.issuer,
        issuedAt: Instant = Instant.now(),
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(accountId.toString())
                .issuer(issuer)
                .audience(audience)
                .claim("roles", roles.toList())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(expiresIn)))
                .jwtID(UUID.randomUUID().toString())
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.EdDSA)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build()
        val signingInput = header.toBase64URL().toString() + "." + claims.toPayload().toBase64URL().toString()
        val signature = Ed25519Jwks.sign(keyPair.private, signingInput.toByteArray(Charsets.US_ASCII))
        return signingInput + "." + Base64URL.encode(signature)
    }

    /** Starts an in-process WireMock serving [jwksJson] at [JWKS_PATH]; idempotent. Returns the server. */
    fun startJwks(): WireMockServer =
        server ?: WireMockServer(options().dynamicPort()).also { wireMock ->
            wireMock.start()
            wireMock.stubFor(
                get(urlEqualTo(JWKS_PATH)).willReturn(
                    aResponse().withHeader("Content-Type", "application/json").withBody(jwksJson()),
                ),
            )
            server = wireMock
        }

    /** URL of the served JWKS, for `platform.security.jwks-uri` (starts the server when needed). */
    val jwksUri: String get() = startJwks().baseUrl() + JWKS_PATH

    override fun close() {
        server?.stop()
        server = null
    }

    companion object {
        const val DEFAULT_ISSUER: String = "https://identity.ecommerce.local"
        const val DEFAULT_AUDIENCE: String = "ecommerce-api"
        const val DEFAULT_KEY_ID: String = "test-key"
        const val JWKS_PATH: String = "/.well-known/jwks.json"
        val DEFAULT_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}

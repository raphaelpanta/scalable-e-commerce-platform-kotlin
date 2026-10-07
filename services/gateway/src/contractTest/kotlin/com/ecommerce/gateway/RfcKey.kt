package com.ecommerce.gateway

import com.ecommerce.gateway.security.Ed25519
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * The private half of the first JWKS example of pact-interactions.md (`x` = `11qYAYKx...`): the Ed25519 test key of
 * RFC 8037 appendix A.1. Public test vector, never a real key.
 */
object RfcKey {
    const val ISSUER = "https://identity.ecommerce.local"
    const val AUDIENCE = "ecommerce-api"
    const val SUBJECT = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
    private val LIFETIME: Duration = Duration.ofMinutes(15)
    private const val D = "nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A"

    // DER prefix of a PKCS#8 PrivateKeyInfo for id-Ed25519 (RFC 8410); the 32 raw private key bytes follow.
    private val PKCS8_PREFIX =
        byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)

    private val privateKey: PrivateKey =
        KeyFactory
            .getInstance(Ed25519.ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + Base64URL(D).decode()))

    /** The public half of the key, `x` of RFC 8037 A.1 (the first JWKS example of pact-interactions.md). */
    const val PUBLIC_X = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"

    /** The JWK Set identity would publish for this key under [kid]. */
    fun jwks(kid: String): String =
        """{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"$kid","x":"$PUBLIC_X","use":"sig","alg":"EdDSA"}]}"""

    /**
     * A 15-minute token with the claims of the shared token contract, signed under [kid]; [issuedAt] moves the
     * lifetime (an access token about to expire for the refresh-ahead states).
     */
    fun token(
        kid: String,
        issuedAt: Instant = Instant.now(),
        roles: List<String> = listOf("shopper"),
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(SUBJECT)
                .claim("roles", roles)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.EdDSA)
                .keyID(kid)
                .type(JOSEObjectType.JWT)
                .build()
        return SignedJWT(header, claims).apply { sign(Signer) }.serialize()
    }

    private object Signer : JWSSigner {
        override fun supportedJWSAlgorithms(): Set<JWSAlgorithm> = setOf(JWSAlgorithm.EdDSA)

        override fun getJCAContext(): JCAContext = JCAContext()

        override fun sign(
            header: JWSHeader,
            signingInput: ByteArray,
        ): Base64URL =
            Signature.getInstance(Ed25519.ALGORITHM).run {
                initSign(privateKey)
                update(signingInput)
                Base64URL.encode(sign())
            }
    }
}

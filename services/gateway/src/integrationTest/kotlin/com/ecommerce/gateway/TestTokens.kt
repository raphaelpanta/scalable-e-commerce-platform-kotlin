package com.ecommerce.gateway

import com.ecommerce.gateway.security.Ed25519
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

const val ISSUER = "https://identity.ecommerce.local"
const val AUDIENCE = "ecommerce-api"
const val SHOPPER_ID = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
const val OPERATOR_ID = "e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"
private val TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)

/** Signs JWS with the JDK's Ed25519 provider (Nimbus's own Ed25519Signer needs Google Tink). */
class JdkEd25519Signer(
    private val privateKey: PrivateKey,
) : JWSSigner {
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

/** An Ed25519 signing key generated for the test run, published under [kid], and tokens signed with it. */
class TestSigningKey(
    val kid: String,
) {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair()

    val publicJwk: OctetKeyPair =
        OctetKeyPair
            .Builder(Curve.Ed25519, Base64URL.encode(Ed25519.rawPublicKey(keyPair.public)))
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.EdDSA)
            .build()

    /**
     * A token following the shared token contract (pact-interactions.md section 4); [adjust] changes claims
     * (for example a wrong audience or issuer) after the defaults are set.
     */
    fun token(
        subject: String = SHOPPER_ID,
        roles: List<String> = listOf("shopper"),
        issuedAt: Instant = Instant.now(),
        headerKid: String = kid,
        adjust: JWTClaimsSet.Builder.() -> Unit = {},
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .claim("roles", roles)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(TOKEN_LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .apply(adjust)
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.EdDSA)
                .keyID(headerKid)
                .type(JOSEObjectType.JWT)
                .build()
        return SignedJWT(header, claims).apply { sign(JdkEd25519Signer(keyPair.private)) }.serialize()
    }

    companion object {
        fun jwks(vararg keys: TestSigningKey): String = JWKSet(keys.map { it.publicJwk }).toString()
    }
}

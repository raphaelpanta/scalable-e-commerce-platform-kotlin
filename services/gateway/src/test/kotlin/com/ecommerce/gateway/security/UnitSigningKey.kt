package com.ecommerce.gateway.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Date

const val UNIT_KID = "unit-key-1"
const val UNIT_ISSUER = "https://identity.ecommerce.local"
const val UNIT_AUDIENCE = "ecommerce-api"
const val UNIT_SUBJECT = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
private val LIFETIME: Duration = Duration.ofMinutes(15)

/**
 * An Ed25519 key generated per test and tokens signed with the JDK provider (the unit-layer twin of the integration
 * tests' TestSigningKey; Nimbus's own Ed25519Signer needs Google Tink).
 */
class UnitSigningKey(
    kid: String = UNIT_KID,
) {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair()

    val publicKey: PublicKey get() = keyPair.public

    val publicJwk: OctetKeyPair =
        OctetKeyPair
            .Builder(Curve.Ed25519, Base64URL.encode(Ed25519.rawPublicKey(keyPair.public)))
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.EdDSA)
            .build()

    fun sign(input: ByteArray): Base64URL =
        Signature.getInstance(Ed25519.ALGORITHM).run {
            initSign(keyPair.private)
            update(input)
            Base64URL.encode(sign())
        }

    /** A token of the shared token contract; [adjust] changes claims after the defaults are set. */
    fun token(
        header: JWSHeader =
            JWSHeader
                .Builder(JWSAlgorithm.EdDSA)
                .keyID(UNIT_KID)
                .type(JOSEObjectType.JWT)
                .build(),
        adjust: JWTClaimsSet.Builder.() -> Unit = {},
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(UNIT_SUBJECT)
                .claim("roles", listOf("shopper"))
                .issuer(UNIT_ISSUER)
                .audience(UNIT_AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .apply(adjust)
                .build()
        val signer =
            object : JWSSigner {
                override fun supportedJWSAlgorithms(): Set<JWSAlgorithm> = setOf(JWSAlgorithm.EdDSA)

                override fun getJCAContext(): JCAContext = JCAContext()

                override fun sign(
                    header: JWSHeader,
                    signingInput: ByteArray,
                ): Base64URL = this@UnitSigningKey.sign(signingInput)
            }
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }
}

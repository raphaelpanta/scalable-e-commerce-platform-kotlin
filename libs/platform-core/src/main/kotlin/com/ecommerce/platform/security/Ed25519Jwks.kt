package com.ecommerce.platform.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.util.Base64URL
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.HexFormat

/**
 * Ed25519 (EdDSA) helpers on the JDK's own provider. Spring Security's JWT support and Nimbus's `Ed25519Verifier`
 * (which needs Google Tink) do not cover the platform's EdDSA tokens, so keys are converted between the JWK `x`
 * value (RFC 8037: the raw 32-byte public key) and JDK keys here; identity uses the same helpers to publish its JWKS.
 */
object Ed25519Jwks {
    /** JDK algorithm name of Ed25519 keys and signatures. */
    const val ALGORITHM: String = "Ed25519"

    /** JWS `alg` values accepted for Ed25519 signatures (RFC 8037 `EdDSA`, RFC 9864 `Ed25519`). */
    val JWS_ALGORITHMS: Set<String> = setOf(JWSAlgorithm.EdDSA.name, ALGORITHM)

    // DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112) followed by the 32-byte key.
    private val X509_PREFIX: ByteArray = HexFormat.of().parseHex("302a300506032b6570032100")
    private const val RAW_KEY_LENGTH = 32

    /** The JDK public key of an Ed25519 [jwk]. */
    fun publicKeyOf(jwk: OctetKeyPair): PublicKey {
        require(jwk.curve == Curve.Ed25519) { "not an Ed25519 key: ${jwk.curve}" }
        val raw = jwk.x.decode()
        require(raw.size == RAW_KEY_LENGTH) { "an Ed25519 public key has $RAW_KEY_LENGTH bytes" }
        return KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
    }

    /** The public JWK of a JDK Ed25519 [publicKey] with [keyId]. */
    fun jwkOf(
        publicKey: PublicKey,
        keyId: String,
    ): OctetKeyPair {
        val encoded = publicKey.encoded
        val raw = encoded.copyOfRange(encoded.size - RAW_KEY_LENGTH, encoded.size)
        return OctetKeyPair
            .Builder(Curve.Ed25519, Base64URL.encode(raw))
            .keyID(keyId)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.EdDSA)
            .build()
    }

    /** The Ed25519 keys of a JWKS document, by key id (`null` for a key without `kid`). */
    fun publicKeys(jwks: JWKSet): Map<String?, PublicKey> =
        jwks.keys
            .filterIsInstance<OctetKeyPair>()
            .filter { it.curve == Curve.Ed25519 }
            .associate { it.keyID to publicKeyOf(it) }

    /** Signs [input] with an Ed25519 private key (64-byte signature). */
    fun sign(
        privateKey: java.security.PrivateKey,
        input: ByteArray,
    ): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(input)
            sign()
        }

    /** Verifies an Ed25519 [signature] of [input]. */
    fun verify(
        publicKey: PublicKey,
        input: ByteArray,
        signature: ByteArray,
    ): Boolean =
        Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey)
            update(input)
            runCatching { verify(signature) }.getOrDefault(false)
        }
}

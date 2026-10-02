package com.ecommerce.gateway.security

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.proc.JWKSecurityContext
import com.nimbusds.jose.proc.JWSKeySelector
import com.nimbusds.jose.proc.JWSVerifierFactory
import com.nimbusds.jose.util.Base64URL
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * EdDSA (Ed25519) verification with the JDK's own provider. Nimbus's `Ed25519Verifier` needs Google Tink, which is
 * not on the classpath, and Nimbus's default key selector and verifier factory know RSA, EC and HMAC only.
 */
object Ed25519 {
    const val ALGORITHM = "Ed25519"
    private const val RAW_KEY_LENGTH = 32

    // DER prefix of an X.509 SubjectPublicKeyInfo for id-Ed25519 (RFC 8410): SEQUENCE { SEQUENCE { OID 1.3.101.112 }
    // BIT STRING (33 bytes, 0 unused bits) }; the 32 raw public key bytes follow.
    private val X509_PREFIX =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** The JDK public key of an OKP JWK with `crv` Ed25519. */
    fun publicKey(jwk: OctetKeyPair): PublicKey {
        val raw = jwk.decodedX
        require(jwk.curve == Curve.Ed25519 && raw.size == RAW_KEY_LENGTH) { "not an Ed25519 public key" }
        return KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
    }

    /** The raw 32-byte public key (the JWK `x` member) of a JDK Ed25519 public key. */
    fun rawPublicKey(key: PublicKey): ByteArray {
        val encoded = key.encoded
        check(encoded.size == X509_PREFIX.size + RAW_KEY_LENGTH) { "not an X.509 encoded Ed25519 key" }
        return encoded.copyOfRange(X509_PREFIX.size, encoded.size)
    }

    /** Whether a JWK of the key set may verify a token with this header. */
    fun isCandidate(
        jwk: JWK,
        header: JWSHeader,
    ): Boolean =
        jwk is OctetKeyPair &&
            jwk.curve == Curve.Ed25519 &&
            (header.keyID == null || header.keyID == jwk.keyID) &&
            (jwk.keyUse == null || jwk.keyUse == KeyUse.SIGNATURE) &&
            (jwk.algorithm == null || jwk.algorithm == JWSAlgorithm.EdDSA)
}

/** Selects the Ed25519 keys of the fetched key set that match the token's `kid`. */
class Ed25519KeySelector : JWSKeySelector<JWKSecurityContext> {
    override fun selectJWSKeys(
        header: JWSHeader,
        context: JWKSecurityContext?,
    ): List<Key> =
        if (header.algorithm != JWSAlgorithm.EdDSA || context == null) {
            emptyList()
        } else {
            context.keys
                .filter { Ed25519.isCandidate(it, header) }
                .map { Ed25519.publicKey(it as OctetKeyPair) }
        }
}

/** Creates [JdkEd25519Verifier]s for EdDSA headers; any other algorithm is refused. */
class Ed25519VerifierFactory : JWSVerifierFactory {
    private val jcaContext = JCAContext()

    override fun supportedJWSAlgorithms(): Set<JWSAlgorithm> = setOf(JWSAlgorithm.EdDSA)

    override fun getJCAContext(): JCAContext = jcaContext

    override fun createJWSVerifier(
        header: JWSHeader,
        key: Key,
    ): JWSVerifier {
        if (header.algorithm != JWSAlgorithm.EdDSA || key !is EdECPublicKey) {
            throw JOSEException("Unsupported JWS algorithm ${header.algorithm} or key type")
        }
        return JdkEd25519Verifier(key)
    }
}

/** Verifies an Ed25519 JWS signature with `java.security.Signature`; tokens with `crit` headers are refused. */
class JdkEd25519Verifier(
    private val publicKey: PublicKey,
) : JWSVerifier {
    private val jcaContext = JCAContext()

    override fun supportedJWSAlgorithms(): Set<JWSAlgorithm> = setOf(JWSAlgorithm.EdDSA)

    override fun getJCAContext(): JCAContext = jcaContext

    override fun verify(
        header: JWSHeader,
        signingInput: ByteArray,
        signature: Base64URL,
    ): Boolean {
        if (header.algorithm != JWSAlgorithm.EdDSA || !header.criticalParams.isNullOrEmpty()) return false
        return try {
            Signature.getInstance(Ed25519.ALGORITHM).run {
                initVerify(publicKey)
                update(signingInput)
                verify(signature.decode())
            }
        } catch (_: GeneralSecurityException) {
            false
        }
    }
}

package com.ecommerce.identity.infrastructure.security

import com.ecommerce.platform.security.Ed25519Jwks
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetKeyPair
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * One Ed25519 signing key of the access tokens. Its [keyId] (`kid`) defaults to the RFC 7638 thumbprint of the
 * public key, so the same key always has the same id (on every instance and after a restart). The private key never
 * leaves this object; `toString()` shows the id only.
 */
class SigningKey(
    val keyId: String,
    val publicKey: PublicKey,
    private val privateKey: PrivateKey,
) {
    /** The public JWK (`kty` OKP, `crv` Ed25519, `use` sig, `alg` EdDSA). */
    val jwk: OctetKeyPair = Ed25519Jwks.jwkOf(publicKey, keyId)

    fun sign(input: ByteArray): ByteArray = Ed25519Jwks.sign(privateKey, input)

    override fun toString(): String = "SigningKey(kid=$keyId)"

    companion object {
        // DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112) before the 32 raw key bytes.
        private val X509_PREFIX: ByteArray = HexFormat.of().parseHex("302a300506032b6570032100")
        private val PEM_ARMOUR = Regex("-----(BEGIN|END) [A-Z ]+-----|\\s")

        /**
         * The configured key (`IDENTITY_SIGNING_KEY`, PKCS#8 as PEM or Base64). Every instance must sign with the same
         * key (FR-024), so a blank one fails start-up unless [generationAllowed] (profile `dev` or `test`), which
         * generates a throw-away pair.
         */
        fun configured(
            encoded: String,
            keyId: String?,
            generationAllowed: Boolean,
        ): SigningKey =
            when {
                encoded.isNotBlank() -> fromPkcs8(encoded, keyId)
                generationAllowed -> generate(keyId)
                else -> error(MISSING_KEY)
            }

        private const val MISSING_KEY: String =
            "IDENTITY_SIGNING_KEY is not set: every identity instance must sign with one shared Ed25519 key " +
                "(FR-024). Generate one with `openssl genpkey -algorithm ed25519 -outform DER | base64`, or " +
                "activate the Spring profile dev or test for a throw-away key (services/identity/README.md)."

        /** A fresh key pair. */
        fun generate(keyId: String? = null): SigningKey {
            val pair = KeyPairGenerator.getInstance(Ed25519Jwks.ALGORITHM).generateKeyPair()
            return SigningKey(keyId ?: thumbprintOf(pair.public), pair.public, pair.private)
        }

        /**
         * The key of an Ed25519 private key in PKCS#8 form, as PEM (`-----BEGIN PRIVATE KEY-----`) or bare Base64
         * of the DER bytes (`openssl genpkey -algorithm ed25519`); the public key is derived from it.
         */
        fun fromPkcs8(
            encoded: String,
            keyId: String? = null,
        ): SigningKey {
            val der = Base64.getMimeDecoder().decode(encoded.replace(PEM_ARMOUR, ""))
            val seed = ASN1OctetString.getInstance(PrivateKeyInfo.getInstance(der).parsePrivateKey()).octets
            val raw = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
            val factory = KeyFactory.getInstance(Ed25519Jwks.ALGORITHM)
            val publicKey = factory.generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(der))
            return SigningKey(keyId ?: thumbprintOf(publicKey), publicKey, privateKey)
        }

        /** The RFC 7638 JWK thumbprint (Base64url SHA-256, 43 characters) of [publicKey]. */
        fun thumbprintOf(publicKey: PublicKey): String = Ed25519Jwks.jwkOf(publicKey, "").computeThumbprint().toString()
    }
}

/**
 * The signing keys of this instance, newest first: the first one signs, all of them are published in the JWKS so
 * that tokens signed before a rotation keep validating (identity-internal.yaml `getJwks`). Empty means no key is
 * available: tokens cannot be issued and the JWKS answers 503.
 */
class SigningKeyRing(
    keys: List<SigningKey>,
) {
    private val keys = AtomicReference(keys.toList())

    /** The key that signs new tokens. */
    val active: SigningKey? get() = keys.get().firstOrNull()

    /** Every published key, newest first. */
    val published: List<SigningKey> get() = keys.get()

    /** The JWK Set of the published keys, or null when there is none. */
    fun jwkSet(): JWKSet? = keys.get().takeIf { it.isNotEmpty() }?.let { published -> JWKSet(published.map { it.jwk }) }

    /** The public key with [keyId]; a token without `kid` matches only a single published key. */
    fun publicKey(keyId: String?): PublicKey? {
        val current = keys.get()
        return if (keyId ==
            null
        ) {
            current.singleOrNull()?.publicKey
        } else {
            current.firstOrNull { it.keyId == keyId }?.publicKey
        }
    }

    /** [next] signs from now on; the previous active key stays published (at least one access-token lifetime). */
    fun rotate(next: SigningKey) {
        keys.updateAndGet { current -> listOf(next) + current.take(1) }
    }

    /** Replaces every key (operations and tests: install a known set, or none). */
    fun replace(replacement: List<SigningKey>) {
        keys.set(replacement.toList())
    }
}

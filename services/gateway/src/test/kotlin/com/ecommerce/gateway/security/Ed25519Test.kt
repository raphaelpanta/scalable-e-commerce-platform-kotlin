package com.ecommerce.gateway.security

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.proc.JWKSecurityContext
import com.nimbusds.jose.util.Base64URL
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

private val EDDSA_HEADER: JWSHeader = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(UNIT_KID).build()

class Ed25519Test :
    FunSpec({
        val key = UnitSigningKey()

        test("an OKP JWK becomes the JDK public key it was made from, and back") {
            val publicKey = Ed25519.publicKey(key.publicJwk)
            publicKey.encoded shouldBe key.publicKey.encoded
            Ed25519.rawPublicKey(publicKey) shouldBe key.publicJwk.decodedX
        }

        test("only 32-byte Ed25519 keys are accepted") {
            val raw = key.publicJwk.x
            shouldThrow<IllegalArgumentException> { Ed25519.publicKey(OctetKeyPair.Builder(Curve.X25519, raw).build()) }
            val short = Base64URL.encode(key.publicJwk.decodedX.copyOf(31))
            shouldThrow<IllegalArgumentException> {
                Ed25519.publicKey(
                    OctetKeyPair.Builder(Curve.Ed25519, short).build(),
                )
            }
            val rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().public
            shouldThrow<IllegalStateException> { Ed25519.rawPublicKey(rsa) }
        }

        context("a key of the set is a candidate when curve, kid, use and algorithm allow it") {
            val raw = key.publicJwk.x
            val ec =
                ECKey
                    .Builder(
                        Curve.P_256,
                        KeyPairGenerator
                            .getInstance("EC")
                            .apply { initialize(ECGenParameterSpec("secp256r1")) }
                            .generateKeyPair()
                            .public as java.security.interfaces.ECPublicKey,
                    ).keyID(UNIT_KID)
                    .build()
            withData(
                nameFn = { (description, _) -> description },
                "same kid, signature use, EdDSA" to (key.publicJwk to true),
                "no use, no algorithm" to (OctetKeyPair.Builder(Curve.Ed25519, raw).keyID(UNIT_KID).build() to true),
                "other kid" to (OctetKeyPair.Builder(key.publicJwk).keyID("other").build() to false),
                "encryption use" to (OctetKeyPair.Builder(key.publicJwk).keyUse(KeyUse.ENCRYPTION).build() to false),
                "other algorithm" to
                    (OctetKeyPair.Builder(key.publicJwk).algorithm(JWSAlgorithm.ES256).build() to false),
                "X25519 curve" to (OctetKeyPair.Builder(Curve.X25519, raw).keyID(UNIT_KID).build() to false),
                "EC key" to (ec to false),
            ) { (_, case) ->
                val (jwk, candidate) = case
                Ed25519.isCandidate(jwk, EDDSA_HEADER) shouldBe candidate
            }
        }

        test("a token without kid may be verified by any Ed25519 key of the set") {
            Ed25519.isCandidate(key.publicJwk, JWSHeader(JWSAlgorithm.EdDSA)).shouldBeTrue()
        }

        test("the key selector returns the matching keys of EdDSA tokens only") {
            val selector = Ed25519KeySelector()
            val other = UnitSigningKey("other")
            val context = JWKSecurityContext(JWKSet(listOf(key.publicJwk, other.publicJwk)).keys)
            selector.selectJWSKeys(EDDSA_HEADER, context).map { it.encoded } shouldBe listOf(key.publicKey.encoded)
            selector.selectJWSKeys(JWSHeader(JWSAlgorithm.ES256), context).shouldBeEmpty()
            selector.selectJWSKeys(EDDSA_HEADER, null).shouldBeEmpty()
        }

        test("the verifier factory creates JDK verifiers for EdDSA and Ed25519 keys only") {
            val factory = Ed25519VerifierFactory()
            factory.supportedJWSAlgorithms() shouldBe setOf(JWSAlgorithm.EdDSA)
            factory.jcaContext.shouldBeInstanceOf<com.nimbusds.jose.jca.JCAContext>()
            factory.createJWSVerifier(EDDSA_HEADER, key.publicKey).shouldBeInstanceOf<JdkEd25519Verifier>()
            shouldThrow<JOSEException> { factory.createJWSVerifier(JWSHeader(JWSAlgorithm.ES256), key.publicKey) }
            val rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().public
            shouldThrow<JOSEException> { factory.createJWSVerifier(EDDSA_HEADER, rsa) }
        }

        test("the JDK verifier accepts the signer's signature and nothing else") {
            val verifier = JdkEd25519Verifier(key.publicKey)
            val input = "header.payload".toByteArray()
            val signature = key.sign(input)
            verifier.supportedJWSAlgorithms() shouldBe setOf(JWSAlgorithm.EdDSA)
            verifier.jcaContext.shouldBeInstanceOf<com.nimbusds.jose.jca.JCAContext>()
            verifier.verify(EDDSA_HEADER, input, signature).shouldBeTrue()
            verifier.verify(EDDSA_HEADER, "header.other".toByteArray(), signature).shouldBeFalse()
            verifier.verify(EDDSA_HEADER, input, UnitSigningKey().sign(input)).shouldBeFalse()
            verifier.verify(EDDSA_HEADER, input, Base64URL.encode(ByteArray(3))).shouldBeFalse()
            verifier.verify(JWSHeader(JWSAlgorithm.ES256), input, signature).shouldBeFalse()
            val critical = JWSHeader.Builder(JWSAlgorithm.EdDSA).criticalParams(setOf("x-unknown")).build()
            verifier.verify(critical, input, signature).shouldBeFalse()
        }
    })

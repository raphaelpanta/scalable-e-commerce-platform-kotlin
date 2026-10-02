package com.ecommerce.identity.infrastructure.security

import com.ecommerce.identity.application.AccessGrant
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.Password
import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.platform.security.Ed25519Jwks
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.springframework.security.oauth2.jwt.BadJwtException
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val CHEAP = Argon2idPasswordHasher.Cost(memoryKib = 64, iterations = 1)
private val PHC =
    Regex("""^\${'$'}argon2id\${'$'}v=19\${'$'}m=64,t=1,p=1\${'$'}[A-Za-z0-9+/]{22}\${'$'}[A-Za-z0-9+/]{43}$""")

private fun password(raw: String = "S3cure-passphrase!"): Password = Password.of(raw, null).fold({ error(it) }, { it })

class SecurityAdaptersSpec :
    FunSpec({
        test("Argon2id hashes are salted PHC strings that verify only the right password") {
            val hasher = Argon2idPasswordHasher(CHEAP)
            val first = hasher.hash(password())
            val second = hasher.hash(password())

            first.value shouldMatch PHC
            first shouldNotBe second
            hasher.verify("S3cure-passphrase!", first) shouldBe true
            hasher.verify("S3cure-passphrase?", first) shouldBe false
            hasher.verify("S3cure-passphrase!", null) shouldBe false
            hasher.verify("S3cure-passphrase!", PasswordHash("\$2a\$10\$bcrypt")) shouldBe false
            hasher.matches("anything", "not a hash") shouldBe false
        }

        test("the default cost is the OWASP baseline") {
            Argon2idPasswordHasher().encode("x") shouldMatch
                Regex("""^\${'$'}argon2id\${'$'}v=19\${'$'}m=19456,t=2,p=1\${'$'}.+""")
        }

        test("generated keys are named by their RFC 7638 thumbprint unless a kid is given") {
            val key = SigningKey.generate()
            key.keyId shouldBe SigningKey.thumbprintOf(key.publicKey)
            key.keyId.length shouldBe 43
            SigningKey.generate("2026-10-a1").keyId shouldBe "2026-10-a1"
            key.toString() shouldBe "SigningKey(kid=${key.keyId})"
            key.jwk.toJSONObject().keys shouldBe setOf("kty", "crv", "kid", "x", "use", "alg")
        }

        test("an imported PKCS#8 key, PEM or bare Base64, keeps its public key and thumbprint") {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val base64 = Base64.getEncoder().encodeToString(pair.private.encoded)
            val pem = "-----BEGIN PRIVATE KEY-----\n${base64.chunked(
                64,
            ).joinToString("\n")}\n-----END PRIVATE KEY-----\n"

            val fromPem = SigningKey.fromPkcs8(pem)
            val fromBase64 = SigningKey.fromPkcs8(base64, "imported")

            fromPem.publicKey.encoded shouldBe pair.public.encoded
            fromPem.keyId shouldBe SigningKey.thumbprintOf(pair.public)
            fromBase64.keyId shouldBe "imported"
            val signature = fromPem.sign("input".toByteArray())
            Ed25519Jwks.verify(pair.public, "input".toByteArray(), signature) shouldBe true
        }

        test("the ring publishes its keys newest first, keeps one previous key on rotation, and may be empty") {
            val a1 = SigningKey.generate("a1")
            val b2 = SigningKey.generate("b2")
            val c3 = SigningKey.generate("c3")
            val ring = SigningKeyRing(listOf(a1))

            ring.rotate(b2)
            ring.published.map { it.keyId } shouldBe listOf("b2", "a1")
            ring.active?.keyId shouldBe "b2"
            ring.publicKey("a1") shouldBe a1.publicKey
            ring.publicKey(null).shouldBeNull()
            ring.jwkSet()?.keys?.map { it.keyID } shouldBe listOf("b2", "a1")
            ring.rotate(c3)
            ring.published.map { it.keyId } shouldBe listOf("c3", "b2")

            ring.replace(listOf(a1))
            ring.publicKey(null) shouldBe a1.publicKey
            ring.replace(emptyList())
            ring.active.shouldBeNull()
            ring.jwkSet().shouldBeNull()
        }

        test("signed access tokens carry the shared claims and validate against the ring only") {
            val ring = SigningKeyRing(listOf(SigningKey.generate("k1")))
            val signer = JwtTokenSigner(ring, "https://identity.ecommerce.local", "ecommerce-api")
            val accountId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val issuedAt = Instant.parse("2026-10-02T10:00:00.123456Z")
            val grant =
                AccessGrant(
                    AccountId(accountId),
                    setOf(Role.OPERATOR, Role.SHOPPER),
                    SessionId(sessionId),
                    issuedAt,
                    Duration.ofMinutes(15),
                )

            val token = signer.sign(grant).shouldNotBeNull()

            token.expiresIn shouldBe Duration.ofMinutes(15)
            token.toString() shouldNotContain token.value
            val jwt = SignedJWT.parse(token.value)
            jwt.header.keyID shouldBe "k1"
            jwt.header.algorithm.name shouldBe "EdDSA"
            jwt.header.type.type shouldBe "JWT"
            val claims = jwt.jwtClaimsSet.toJSONObject()
            claims["sub"] shouldBe accountId.toString()
            claims["roles"] shouldBe listOf("shopper", "operator")
            claims["iss"] shouldBe "https://identity.ecommerce.local"
            claims["aud"] shouldBe "ecommerce-api"
            claims["iat"] shouldBe issuedAt.epochSecond
            (claims["exp"] as Long) - (claims["iat"] as Long) shouldBe 900L
            claims["sid"] shouldBe sessionId.toString()
            UUID.fromString(claims["jti"] as String).shouldNotBeNull()

            val processor = KeyRingJwtProcessor(ring)
            processor.convert(jwt).block()?.subject shouldBe accountId.toString()
            KeyRingJwtProcessor(SigningKeyRing(listOf(SigningKey.generate("k1")))).convert(jwt).let { invalid ->
                shouldThrow<BadJwtException> { invalid.block() }.message shouldBe "Invalid signature"
            }
            KeyRingJwtProcessor(SigningKeyRing(emptyList())).convert(jwt).let { unknown ->
                shouldThrow<BadJwtException> { unknown.block() }.message shouldBe "Unknown signing key"
            }
            ring.replace(emptyList())
            signer.sign(grant).shouldBeNull()
        }

        test("secrets are 43-character tokens, 6-digit codes and distinct ids") {
            val secrets = SecureSecrets()
            secrets.opaqueToken().value shouldMatch Regex("[A-Za-z0-9_-]{43}")
            repeat(50) { secrets.verificationCode().value shouldMatch Regex("[0-9]{6}") }
            secrets.newId() shouldNotBe secrets.newId()
            val clock =
                SystemClock(
                    java.time.Clock.fixed(Instant.parse("2026-10-02T10:00:00.123456789Z"), java.time.ZoneOffset.UTC),
                )
            clock.now() shouldBe Instant.parse("2026-10-02T10:00:00.123456Z")
        }
    })

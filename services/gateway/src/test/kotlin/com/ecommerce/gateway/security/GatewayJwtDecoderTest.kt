package com.ecommerce.gateway.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.JWK
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.security.oauth2.jwt.JwtException
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.Date

private fun decoderWith(keys: (String?) -> Mono<List<JWK>>) =
    GatewayJwtDecoder.create(
        mockk<JwksClient> { every { keysFor(any()) } answers { keys(firstArg()) } },
        UNIT_ISSUER,
        UNIT_AUDIENCE,
    )

class GatewayJwtDecoderTest :
    FunSpec({
        val key = UnitSigningKey()
        val decoder = decoderWith { kid -> Mono.just(listOf(key.publicJwk).filter { it.keyID == kid }) }

        test("a token of the contract signed by a published key is accepted with its claims") {
            val jwt = decoder.decode(key.token()).block()!!
            jwt.subject shouldBe UNIT_SUBJECT
            jwt.getClaimAsStringList("roles") shouldBe listOf("shopper")
        }

        context("a token outside the contract is refused") {
            val hourAgo = Date.from(Instant.now().minusSeconds(3600))
            withData(
                nameFn = { (description, _) -> description },
                "wrong issuer" to key.token { issuer("https://elsewhere") },
                "wrong audience" to key.token { audience("other-api") },
                "expired" to key.token { expirationTime(hourAgo) },
                "not yet valid" to key.token { notBeforeTime(Date.from(Instant.now().plusSeconds(3600))) },
                "blank subject" to key.token { subject(" ") },
                "no subject" to key.token { subject(null) },
                "signed by an unpublished key" to UnitSigningKey().token(),
                "unknown kid" to key.token(JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID("rotated").build()),
                "wrong type" to
                    key.token(
                        JWSHeader
                            .Builder(JWSAlgorithm.EdDSA)
                            .keyID(UNIT_KID)
                            .type(JOSEObjectType("secevent+jwt"))
                            .build(),
                    ),
            ) { (_, token) ->
                shouldThrow<JwtException> { decoder.decode(token).block() }
                    .shouldNotBeInstanceOfUnavailable()
            }
        }

        test("signing keys that cannot be read surface as JwksUnavailableException, never as success") {
            val unavailable = decoderWith { Mono.error(JwksUnavailableException("JWKS document answered 503")) }
            shouldThrow<JwksUnavailableException> { unavailable.decode(key.token()).block() }
        }

        test("other key-source failures are not reported as unavailable keys") {
            val broken = decoderWith { Mono.error(IllegalArgumentException("broken")) }
            val failure = shouldThrow<Exception> { broken.decode(key.token()).block() }
            failure shouldNotBe null
            generateSequence<Throwable>(failure) { it.cause }
                .none { it is JwksUnavailableException } shouldBe true
        }
    })

private fun JwtException.shouldNotBeInstanceOfUnavailable() {
    (this is JwksUnavailableException) shouldBe false
    this.shouldBeInstanceOf<JwtException>()
}

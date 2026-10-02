package com.ecommerce.gateway.security

import org.springframework.security.oauth2.jose.jws.JwsAlgorithm
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder

/**
 * Builds the access-token decoder: Spring's [NimbusReactiveJwtDecoder] over the cached JWK Set of [JwksClient], with
 * EdDSA (Ed25519) signatures verified by the JDK ([Ed25519KeySelector], [Ed25519VerifierFactory]) and the claims of
 * the shared token contract (pact-interactions.md section 4) validated: `exp`/`nbf` (60 s skew), `iss`, `aud`
 * (string or array containing it), `typ` JWT when present, and a non-blank `sub`.
 */
object GatewayJwtDecoder {
    private val EDDSA = JwsAlgorithm { "EdDSA" }

    fun create(
        jwks: JwksClient,
        issuer: String,
        audience: String,
    ): ReactiveJwtDecoder {
        val nimbus =
            NimbusReactiveJwtDecoder
                .withJwkSource { jwt -> jwks.keysFor(jwt.header.keyID).flatMapIterable { it } }
                .jwsAlgorithm(EDDSA)
                .jwtProcessorCustomizer { processor ->
                    processor.setJWSKeySelector(Ed25519KeySelector())
                    processor.setJWSVerifierFactory(Ed25519VerifierFactory())
                }.build()
        nimbus.setJwtValidator(
            JwtValidators.createDefaultWithValidators(
                JwtIssuerValidator(issuer),
                JwtAudienceValidator(audience),
                JwtClaimValidator<Any>(JwtClaimNames.SUB) { claim -> claim is String && claim.isNotBlank() },
            ),
        )
        // Spring reports a failed key lookup as IllegalStateException; surface it as a JwtException so that the
        // resource server turns it into an AuthenticationServiceException (answered 503), never into a success.
        return ReactiveJwtDecoder { token ->
            nimbus.decode(token).onErrorMap(::isKeyLookupFailure) { failure ->
                JwksUnavailableException("signing keys are unavailable", failure)
            }
        }
    }

    private fun isKeyLookupFailure(error: Throwable): Boolean =
        error !is JwtException && generateSequence(error) { it.cause }.any { it is JwksUnavailableException }
}

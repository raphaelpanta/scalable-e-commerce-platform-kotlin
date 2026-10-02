package com.ecommerce.identity.infrastructure.security

import com.ecommerce.identity.application.AccessGrant
import com.ecommerce.identity.application.SignedAccessToken
import com.ecommerce.identity.application.TokenSigner
import com.ecommerce.platform.security.Ed25519Jwks
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.core.convert.converter.Converter
import org.springframework.security.oauth2.jwt.BadJwtException
import reactor.core.publisher.Mono
import java.text.ParseException
import java.util.Date
import java.util.UUID

/**
 * Outbound adapter: issues the platform's access tokens (pact-interactions.md section 4) as EdDSA (Ed25519) JWTs
 * signed with the active key of [ring]: header `alg` EdDSA, `kid`, `typ` JWT; claims `sub` (account id), `roles`,
 * `iss`, `aud` (a string), `iat`, `exp` (`exp - iat` = the grant's lifetime, 900 s), `jti` (UUID) and the additive
 * `sid` (the session, read back by sign-out). Answers null while the ring holds no key.
 */
class JwtTokenSigner(
    private val ring: SigningKeyRing,
    private val issuer: String,
    private val audience: String,
) : TokenSigner {
    override suspend fun sign(grant: AccessGrant): SignedAccessToken? {
        val key = ring.active ?: return null
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(grant.accountId.value.toString())
                .claim(ROLES, grant.roles.sortedBy { it.ordinal }.map { it.code })
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(grant.issuedAt))
                .expirationTime(Date.from(grant.issuedAt.plus(grant.lifetime)))
                .jwtID(UUID.randomUUID().toString())
                .claim(SESSION, grant.sessionId.value.toString())
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.EdDSA)
                .keyID(key.keyId)
                .type(JOSEObjectType.JWT)
                .build()
        val signingInput = header.toBase64URL().toString() + "." + claims.toPayload().toBase64URL().toString()
        val signature = key.sign(signingInput.toByteArray(Charsets.US_ASCII))
        return SignedAccessToken(signingInput + "." + Base64URL.encode(signature), grant.lifetime)
    }

    companion object {
        const val ROLES: String = "roles"

        /** Additive claim: the session the token belongs to. */
        const val SESSION: String = "sid"
    }
}

/**
 * Verifies the signature of access tokens against the keys of [ring] directly, so identity validates its own tokens
 * without fetching its own JWKS over HTTP; plugged into `NimbusReactiveJwtDecoder` with the platform validators
 * (issuer, audience, expiry, UUID subject). Bad tokens fail with [BadJwtException] (401).
 */
class KeyRingJwtProcessor(
    private val ring: SigningKeyRing,
) : Converter<JWT, Mono<JWTClaimsSet>> {
    override fun convert(jwt: JWT): Mono<JWTClaimsSet> {
        val key = (jwt as? SignedJWT)?.let { ring.publicKey(it.header.keyID) }
        return when {
            jwt !is SignedJWT -> {
                Mono.error(BadJwtException("Unsigned tokens are not accepted"))
            }

            jwt.header.algorithm.name !in Ed25519Jwks.JWS_ALGORITHMS -> {
                Mono.error(
                    BadJwtException("Unsupported algorithm"),
                )
            }

            key == null -> {
                Mono.error(BadJwtException("Unknown signing key"))
            }

            !Ed25519Jwks.verify(
                key,
                jwt.signingInput,
                jwt.signature.decode(),
            ) -> {
                Mono.error(BadJwtException("Invalid signature"))
            }

            else -> {
                claimsOf(jwt)
            }
        }
    }

    private fun claimsOf(jwt: SignedJWT): Mono<JWTClaimsSet> =
        try {
            Mono.just(jwt.jwtClaimsSet)
        } catch (malformed: ParseException) {
            Mono.error(BadJwtException("Malformed claims", malformed))
        }
}

package com.ecommerce.platform.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.core.convert.converter.Converter
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.net.URI
import java.security.PublicKey
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the signature of EdDSA (Ed25519) access tokens against the identity service's JWKS and returns their
 * claims; plugged into `NimbusReactiveJwtDecoder`, which then applies the issuer, audience and timestamp validators.
 *
 * Keys are cached for [cacheTtl] and re-fetched early when a token names an unknown `kid` (at most once per
 * [minRefreshInterval]). When the JWKS cannot be fetched the last known keys keep working; without any known key the
 * failure is a plain [JwtException], which the resource server turns into a 503 problem instead of accepting or
 * blaming the token (contracts/gateway-routes.md, "JWT validation"). Bad tokens fail with [BadJwtException] (401).
 */
class Ed25519JwksJwtProcessor(
    private val jwksUri: URI,
    private val webClient: WebClient,
    private val cacheTtl: Duration,
    private val minRefreshInterval: Duration = Duration.ofSeconds(DEFAULT_MIN_REFRESH_SECONDS),
    private val clock: Clock = Clock.systemUTC(),
) : Converter<JWT, Mono<JWTClaimsSet>> {
    private val snapshot = AtomicReference<KeySnapshot?>(null)

    private class KeySnapshot(
        val keys: Map<String?, PublicKey>,
        val fetchedAt: Instant,
    )

    override fun convert(jwt: JWT): Mono<JWTClaimsSet> =
        when {
            jwt !is SignedJWT -> {
                Mono.error(BadJwtException("Unsigned tokens are not accepted"))
            }

            jwt.header.algorithm.name !in Ed25519Jwks.JWS_ALGORITHMS -> {
                Mono.error(BadJwtException("Unsupported algorithm ${jwt.header.algorithm}"))
            }

            else -> {
                keyFor(jwt.header.keyID).map { key -> verifiedClaims(jwt, key) }
            }
        }

    private fun verifiedClaims(
        signed: SignedJWT,
        key: PublicKey,
    ): JWTClaimsSet {
        if (!Ed25519Jwks.verify(key, signed.signingInput, signed.signature.decode())) {
            throw BadJwtException("Invalid signature")
        }
        return try {
            signed.jwtClaimsSet
        } catch (ex: ParseException) {
            throw BadJwtException("Malformed claims", ex)
        }
    }

    private fun keyFor(kid: String?): Mono<PublicKey> {
        val current = snapshot.get()
        val now = clock.instant()
        val cached = current?.takeIf { it.fetchedAt.plus(cacheTtl).isAfter(now) }?.let { select(it, kid) }
        return when {
            cached != null -> {
                Mono.just(cached)
            }

            current != null && current.fetchedAt.plus(minRefreshInterval).isAfter(now) -> {
                select(current, kid)?.let { Mono.just(it) } ?: Mono.error(BadJwtException("Unknown signing key"))
            }

            else -> {
                refreshed(kid, current)
            }
        }
    }

    private fun refreshed(
        kid: String?,
        previous: KeySnapshot?,
    ): Mono<PublicKey> =
        fetch()
            .onErrorResume { error ->
                if (previous != null) {
                    Mono.just(previous)
                } else {
                    Mono.error(JwtException("The signing keys cannot be fetched from $jwksUri", error))
                }
            }.flatMap { keys ->
                select(keys, kid)?.let { Mono.just(it) }
                    ?: Mono.error(BadJwtException("Unknown signing key"))
            }

    private fun fetch(): Mono<KeySnapshot> =
        webClient
            .get()
            .uri(jwksUri)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono<String>()
            .timeout(FETCH_TIMEOUT)
            .map { KeySnapshot(Ed25519Jwks.publicKeys(JWKSet.parse(it)), clock.instant()) }
            .doOnNext(snapshot::set)

    private fun select(
        snapshot: KeySnapshot,
        kid: String?,
    ): PublicKey? = if (kid != null) snapshot.keys[kid] else snapshot.keys.values.singleOrNull()

    private companion object {
        const val DEFAULT_MIN_REFRESH_SECONDS = 10L
        val FETCH_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

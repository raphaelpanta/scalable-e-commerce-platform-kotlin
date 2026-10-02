package com.ecommerce.gateway.security

import com.ecommerce.gateway.correlation.CorrelationIds
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** The JWKS document could not be fetched and no cached key matches: protected routes answer 503. */
class JwksUnavailableException : JwtException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/**
 * Reads the identity service's JWK Set (`GET /.well-known/jwks.json`, contract `identity-internal.yaml`, pact
 * `gateway` -> `identity`) and caches it:
 *
 * - a fetched set is reused for [cacheTtl], then re-read on the next request (scheduled refresh);
 * - a `kid` missing from the set forces one refresh, at most once per [refreshCooldown] (key rotation);
 * - concurrent requests share one in-flight fetch; a failed fetch is remembered for [refreshCooldown];
 * - when a fetch fails, the keys of the last successful fetch keep validating; when none of them matches, the
 *   request fails with [JwksUnavailableException] and is never accepted unverified.
 */
class JwksClient(
    private val webClient: WebClient,
    private val jwksUri: URI,
    private val cacheTtl: Duration,
    private val refreshCooldown: Duration,
    private val fetchTimeout: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lastGood = AtomicReference<JWKSet?>(null)
    private val lastForcedRefresh = AtomicReference(Instant.EPOCH)
    private val cycle = AtomicReference(newCycle())

    /** The keys of the set whose `kid` equals [kid] (every key when the token names none). */
    fun keysFor(kid: String?): Mono<List<JWK>> = keysFrom(cycle.get(), kid, mayRefresh = true)

    /** One fetch of the key set, without caching. */
    fun fetch(): Mono<JWKSet> =
        Mono
            .deferContextual { context ->
                webClient
                    .get()
                    .uri(jwksUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(
                        CorrelationIds.HEADER,
                        context.getOrEmpty<Any>(CorrelationIds.CONTEXT_KEY).map(Any::toString).orElseGet(::newId),
                    ).exchangeToMono<String> { response ->
                        if (response.statusCode() == HttpStatusCode.valueOf(OK)) {
                            response.bodyToMono(String::class.java)
                        } else {
                            response.releaseBody().then(
                                Mono.error<String>(unavailable("answered ${response.statusCode()}")),
                            )
                        }
                    }
            }.timeout(fetchTimeout)
            .map(::parse)
            .onErrorMap({ it !is JwksUnavailableException }) { unavailable("could not be read", it) }
            .doOnNext(lastGood::set)

    private fun keysFrom(
        current: Mono<JWKSet>,
        kid: String?,
        mayRefresh: Boolean,
    ): Mono<List<JWK>> =
        current
            .flatMap { set ->
                val matching = select(set, kid)
                when {
                    matching.isNotEmpty() -> {
                        Mono.just(matching)
                    }

                    mayRefresh && (cycle.get() !== current || claimForcedRefresh()) -> {
                        cycle.compareAndSet(current, newCycle())
                        keysFrom(cycle.get(), kid, mayRefresh = false)
                    }

                    else -> {
                        Mono.just(emptyList())
                    }
                }
            }.onErrorResume(JwksUnavailableException::class.java) { failure ->
                val cached = lastGood.get()?.let { select(it, kid) }.orEmpty()
                if (cached.isNotEmpty()) Mono.just(cached) else Mono.error(failure)
            }

    private fun newCycle(): Mono<JWKSet> = fetch().cache({ cacheTtl }, { refreshCooldown }, { Duration.ZERO })

    private fun claimForcedRefresh(): Boolean {
        val now = clock.instant()
        val previous = lastForcedRefresh.get()
        return previous.plus(refreshCooldown) <= now && lastForcedRefresh.compareAndSet(previous, now)
    }

    private fun select(
        set: JWKSet,
        kid: String?,
    ): List<JWK> = if (kid == null) set.keys else set.keys.filter { it.keyID == kid }

    private fun parse(body: String): JWKSet =
        try {
            JWKSet.parse(body)
        } catch (invalid: ParseException) {
            throw unavailable("is not a JWK Set", invalid)
        }

    private fun unavailable(
        reason: String,
        cause: Throwable? = null,
    ): JwksUnavailableException {
        val message = "JWKS document at $jwksUri $reason"
        return if (cause == null) JwksUnavailableException(message) else JwksUnavailableException(message, cause)
    }

    private companion object {
        const val OK = 200

        fun newId(): String = CorrelationIds.newId()
    }
}

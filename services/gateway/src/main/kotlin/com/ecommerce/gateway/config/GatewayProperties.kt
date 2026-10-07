package com.ecommerce.gateway.config

import com.ecommerce.gateway.routing.Tier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

private const val CACHE_TTL_MINUTES = 5L
private const val REFRESH_COOLDOWN_SECONDS = 10L
private const val FETCH_TIMEOUT_SECONDS = 2L
private const val IDLE_TIMEOUT_MINUTES = 30L
private const val REFRESH_AHEAD_SECONDS = 60L
private const val CART_COOKIE_MAX_AGE_DAYS = 30L
private const val REFRESH_TIMEOUT_SECONDS = 5L

/**
 * Gateway settings under `gateway.*` (application.yml). Routes themselves live in
 * `spring.cloud.gateway.server.webflux.routes`; each route names its auth requirement and rate-limit tier in its
 * metadata ([com.ecommerce.gateway.routing.RoutePolicy]).
 */
@ConfigurationProperties("gateway")
data class GatewayProperties(
    val jwt: Jwt,
    val rateLimit: RateLimit = RateLimit(),
    /** Default request body limit; a route may raise or lower it with the `max-body-size` metadata entry. */
    val maxBodySize: DataSize = DataSize.ofMegabytes(1),
    val browserSession: BrowserSessionProperties = BrowserSessionProperties(),
) {
    /**
     * The browser session of the storefront (`gateway.browser-session.*`, feature 005): the sealing key
     * `BROWSER_SESSION_KEY` (required outside the `dev`/`test` profiles, checked by
     * [com.ecommerce.gateway.browser.BrowserSessionKeys]), the idle window, the refresh-ahead window, the cart
     * cookie lifetime and the identity instance that rotates refresh tokens.
     */
    data class BrowserSessionProperties(
        /** Base64 of 32 random bytes; blank means "not configured". Never logged. */
        val key: String = "",
        /** `IDENTITY_URL`: where `POST /api/v1/identity/sessions/refresh` is sent. */
        val identityUrl: URI = URI("http://localhost:8080"),
        val idleTimeout: Duration = Duration.ofMinutes(IDLE_TIMEOUT_MINUTES),
        val refreshAhead: Duration = Duration.ofSeconds(REFRESH_AHEAD_SECONDS),
        val cartCookieMaxAge: Duration = Duration.ofDays(CART_COOKIE_MAX_AGE_DAYS),
        val refreshTimeout: Duration = Duration.ofSeconds(REFRESH_TIMEOUT_SECONDS),
    ) {
        override fun toString(): String =
            "BrowserSessionProperties(key=${if (key.isBlank()) "<generated>" else "<set>"}, " +
                "identityUrl=$identityUrl, idleTimeout=$idleTimeout, refreshAhead=$refreshAhead, " +
                "cartCookieMaxAge=$cartCookieMaxAge, refreshTimeout=$refreshTimeout)"
    }

    /** Access-token validation against the identity JWKS (`JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`). */
    data class Jwt(
        val jwksUri: URI,
        val issuer: String,
        val audience: String,
        /** A fetched key set is re-read after this age (scheduled refresh); stale keys stay usable on failure. */
        val cacheTtl: Duration = Duration.ofMinutes(CACHE_TTL_MINUTES),
        /** Minimum gap between two forced refreshes caused by unknown `kid` values, and failure back-off. */
        val refreshCooldown: Duration = Duration.ofSeconds(REFRESH_COOLDOWN_SECONDS),
        val fetchTimeout: Duration = Duration.ofSeconds(FETCH_TIMEOUT_SECONDS),
    )

    /** Requests per minute per client key for each tier (contracts/gateway-routes.md, "Rate limit tiers"). */
    data class RateLimit(
        val requestsPerMinute: Map<Tier, Int> = Tier.entries.associateWith(Tier::defaultRequestsPerMinute),
    ) {
        fun limitOf(tier: Tier): Int = requestsPerMinute[tier] ?: tier.defaultRequestsPerMinute
    }
}

package com.ecommerce.gateway.config

import com.ecommerce.gateway.routing.Tier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

private const val CACHE_TTL_MINUTES = 5L
private const val REFRESH_COOLDOWN_SECONDS = 10L
private const val FETCH_TIMEOUT_SECONDS = 2L

/**
 * Gateway settings under `gateway.*` (application.yml). Routes themselves live in
 * `spring.cloud.gateway.server.webflux.routes`; each route names its auth requirement and rate-limit tier in its
 * metadata ([com.ecommerce.gateway.routing.RoutePolicy]).
 */
@ConfigurationProperties("gateway")
data class GatewayProperties(
    val jwt: Jwt,
    val rateLimit: RateLimit = RateLimit(),
    /** Default request body limit; a route may raise it with the `max-body-size` metadata entry. */
    val maxBodySize: DataSize = DataSize.ofMegabytes(1),
) {
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

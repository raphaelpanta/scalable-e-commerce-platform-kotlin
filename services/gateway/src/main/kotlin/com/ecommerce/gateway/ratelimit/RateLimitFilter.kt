package com.ecommerce.gateway.ratelimit

import com.ecommerce.gateway.config.GatewayProperties
import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import com.ecommerce.gateway.routing.CALLER_ATTRIBUTE
import com.ecommerce.gateway.routing.Caller
import com.ecommerce.gateway.routing.ClientKey
import com.ecommerce.gateway.routing.RoutePolicies
import com.ecommerce.gateway.routing.Tier
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Charges each routed request to the budget of its tier (the route's `tier` metadata, or `operator-tier` for
 * operators) and client key: the source address for the `auth` tier, otherwise the account id of an authenticated
 * caller or the source address. Over budget the request is not forwarded and answers 429 `throttled` with
 * `Retry-After` (seconds).
 */
@Component
class RateLimitFilter(
    private val policies: RoutePolicies,
    properties: GatewayProperties,
) : GlobalFilter,
    Ordered {
    private val limiter = InMemoryRateLimiter()
    private val limits: Map<Tier, TokenBucket.Limit> =
        Tier.entries.associateWith { TokenBucket.Limit(properties.rateLimit.limitOf(it)) }

    override fun getOrder(): Int = ORDER

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val policy = policies.of(exchange) ?: return chain.filter(exchange)
        val caller = exchange.getAttribute<Caller>(CALLER_ATTRIBUTE)
        val tier = policy.tierFor(caller)
        return when (val decision = limiter.tryAcquire(clientKey(exchange, tier, caller), limits.getValue(tier))) {
            is TokenBucket.Decision.Allowed -> {
                chain.filter(exchange)
            }

            is TokenBucket.Decision.Rejected -> {
                Mono.error(
                    GatewayProblemException(
                        GatewayProblem.THROTTLED,
                        headers = mapOf(HttpHeaders.RETRY_AFTER to decision.retryAfterSeconds.toString()),
                    ),
                )
            }
        }
    }

    private fun clientKey(
        exchange: ServerWebExchange,
        tier: Tier,
        caller: Caller?,
    ): String {
        val client =
            if (tier.clientKey == ClientKey.ACCOUNT_OR_ADDRESS && caller != null) {
                "account:${caller.accountId}"
            } else {
                "address:${exchange.request.remoteAddress
                    ?.address
                    ?.hostAddress ?: UNKNOWN_ADDRESS}"
            }
        return "${tier.id}|$client"
    }

    private companion object {
        /** After [com.ecommerce.gateway.routing.RouteAccessFilter], which identifies the caller. */
        const val ORDER = -200
        const val UNKNOWN_ADDRESS = "unknown"
    }
}

package com.ecommerce.gateway.web

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Per-route response headers ([EdgeHeaders.policyFor]): the storefront route answers with the page policy (its CSP
 * and `Permissions-Policy`) instead of the API one. Registered as a before-commit action after the edge decorator's,
 * so it runs after [EdgeHeaders.harden] and replaces what that set. Upstream cache headers pass through untouched.
 */
@Component
class RouteHeadersFilter :
    GlobalFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val route =
            exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) ?: return chain.filter(exchange)
        val policy = EdgeHeaders.policyFor(route.id)
        if (policy.isNotEmpty()) {
            val response = exchange.response
            response.beforeCommit {
                policy.forEach(response.headers::set)
                Mono.empty()
            }
        }
        return chain.filter(exchange)
    }

    private companion object {
        /** Anywhere before the forwarding filters; -100 keeps it after the policy filters. */
        const val ORDER = -100
    }
}

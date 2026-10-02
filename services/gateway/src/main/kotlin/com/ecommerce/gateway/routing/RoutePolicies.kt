package com.ecommerce.gateway.routing

import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.cloud.gateway.config.GatewayProperties as RouteTable

/**
 * The parsed policy of every configured route. Built once at start-up from the route table, so a route without an
 * explicit `auth`, a known `tier` and the tier's `response-timeout` stops the gateway from starting
 * (deny by default: no route exists without an explicit auth requirement).
 */
@Component
class RoutePolicies(
    routeTable: RouteTable,
) {
    private val byRouteId: Map<String, RoutePolicy> =
        routeTable.routes.associate { definition ->
            val id = requireNotNull(definition.id) { "every route needs an id" }
            id to RoutePolicy.from(id, definition.metadata)
        }

    /** The policy of the route this exchange was matched to, or null before routing / for unrouted requests. */
    fun of(exchange: ServerWebExchange): RoutePolicy? =
        exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)?.let { route ->
            byRouteId[route.id] ?: RoutePolicy.from(route.id, route.metadata)
        }
}

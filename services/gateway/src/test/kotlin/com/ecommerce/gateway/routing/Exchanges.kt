package com.ecommerce.gateway.routing

import com.ecommerce.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.security.Principal
import org.springframework.cloud.gateway.config.GatewayProperties as RouteTableProperties

const val UPSTREAM = "http://upstream:8080"

/** Route metadata as application.yml declares it. */
fun metadata(
    auth: String,
    tier: String,
    vararg extra: Pair<String, Any>,
): Map<String, Any> =
    mapOf(
        RoutePolicy.AUTH to auth,
        RoutePolicy.TIER to tier,
        RoutePolicy.RESPONSE_TIMEOUT to Tier.of(tier).upstreamTimeout.toMillis(),
    ) + extra

/** A matched route, as Spring Cloud Gateway leaves it in the exchange. */
fun route(
    id: String,
    metadata: Map<String, Any>,
): Route =
    Route
        .async()
        .id(id)
        .uri(URI(UPSTREAM))
        .predicate { true }
        .metadata(metadata)
        .build()

/** The parsed policies of [routes], built like the application's route table. */
fun policiesOf(vararg routes: Route): RoutePolicies =
    RoutePolicies(
        RouteTableProperties().apply {
            this.routes =
                routes
                    .map { route ->
                        RouteDefinition().apply {
                            setId(route.id)
                            setUri(route.uri)
                            setMetadata(route.metadata)
                        }
                    }.toMutableList()
        },
    )

fun gatewayProperties(configure: GatewayProperties.() -> GatewayProperties = { this }): GatewayProperties =
    GatewayProperties(GatewayProperties.Jwt(URI("$UPSTREAM/.well-known/jwks.json"), "issuer", "audience")).configure()

/** An exchange matched to [route] (none: unrouted), with an optional authenticated [principal]. */
fun exchange(
    request: MockServerHttpRequest = MockServerHttpRequest.get("/api/v1/resource").build(),
    route: Route? = null,
    principal: Principal? = null,
): MockServerWebExchange {
    val builder = MockServerWebExchange.builder(request)
    principal?.let(builder::principal)
    return builder.build().also { exchange ->
        route?.let { exchange.attributes[ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR] = it }
    }
}

/** The bearer-token authentication Spring Security leaves for a valid token. */
fun authenticated(
    subject: String?,
    roles: List<String>? = null,
): JwtAuthenticationToken {
    val builder =
        Jwt
            .withTokenValue("token")
            .header("alg", "EdDSA")
            .claim("iss", "issuer")
    subject?.let(builder::subject)
    roles?.let { builder.claim("roles", it) }
    return JwtAuthenticationToken(builder.build())
}

/** A filter chain that records the exchange it was called with. */
class RecordingChain : GatewayFilterChain {
    var forwarded: ServerWebExchange? = null
        private set

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(exchange: ServerWebExchange): Mono<Void> {
        forwarded = exchange
        return Mono.empty()
    }
}

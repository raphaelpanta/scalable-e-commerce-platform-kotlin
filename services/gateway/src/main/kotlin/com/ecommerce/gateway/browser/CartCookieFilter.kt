package com.ecommerce.gateway.browser

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Duration

/** The cart contract's anonymous credential header. */
const val CART_TOKEN_HEADER = "X-Cart-Token"

/**
 * The anonymous cart cookie (gateway-browser-session.yaml "Anonymous cart", research section 3), active only when the
 * request carries `X-Browser-Session: cookie`:
 *
 * - a cart cookie (one name) is unsealed and injected as `X-Cart-Token` when the client sent none; an explicit header
 *   wins; an unsealable cookie, or both names at once, is deleted and ignored;
 * - an upstream `X-Cart-Token` is sealed into the cart cookie (`Max-Age` 30 days) and removed from the response;
 * - a 200 from `POST /api/v1/cart/merge` deletes the cookie (the anonymous cart was consumed); other statuses keep it.
 *
 * Runs after [BrowserSessionFilter] (the merge needs the bearer) and before the route access filter.
 */
class CartCookieFilter(
    private val sealer: SessionSealer,
    private val maxAge: Duration,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val request = exchange.request
        val route =
            exchange
                .getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
                ?.takeIf { RouteKind.of(it.id, request.path.value()) != RouteKind.IGNORED && browserMode(request) }
                ?: return chain.filter(exchange)
        val cookies = cartCookies(request)
        val deletions = mutableListOf<String>()
        val unsealed = cookies.nameOf(CookieKind.CART)?.let { name -> unseal(cookies, name, deletions) }
        if (cookies.both) deletions += BrowserCookies.deleteBoth(CookieKind.CART)
        onResponse(exchange.response, route.id, Transport.of(request.uri.scheme), deletions)
        val injected = unsealed?.takeIf { request.headers.getFirst(CART_TOKEN_HEADER).isNullOrBlank() }
        return chain.filter(if (injected == null) exchange else withToken(exchange, injected))
    }

    /** The token of the single cart cookie, or null (and a deletion) when it cannot be unsealed. */
    private fun unseal(
        cookies: CookiePair,
        name: CookieName,
        deletions: MutableList<String>,
    ): String? {
        val token = cookies.single?.let(sealer::unsealToken)
        if (token == null) deletions += BrowserCookies.delete(name)
        return token
    }

    private fun onResponse(
        response: ServerHttpResponse,
        routeId: String,
        transport: Transport,
        deletions: List<String>,
    ) {
        response.beforeCommit {
            val headers = response.headers
            deletions.forEach { headers.add(HttpHeaders.SET_COOKIE, it) }
            val returned = headers.getFirst(CART_TOKEN_HEADER)
            headers.remove(CART_TOKEN_HEADER)
            if (routeId == MERGE_ROUTE && response.statusCode == HttpStatus.OK) {
                headers.add(HttpHeaders.SET_COOKIE, BrowserCookies.delete(transport.cart))
            } else if (!returned.isNullOrBlank()) {
                mirror(headers, transport, returned)
            }
            Mono.empty()
        }
    }

    /** The upstream token becomes the cart cookie; over the budget the header passes through instead. */
    private fun mirror(
        headers: HttpHeaders,
        transport: Transport,
        returned: String,
    ) {
        val cookie = BrowserCookies.set(transport.cart, sealer.sealToken(returned), maxAge)
        if (cookie == null) {
            headers.set(CART_TOKEN_HEADER, returned)
        } else {
            headers.add(HttpHeaders.SET_COOKIE, cookie)
            headers.add(HttpHeaders.SET_COOKIE, BrowserCookies.delete(transport.cart.counterpart))
        }
    }

    companion object {
        /** After [BrowserSessionFilter] (-400), before the route access filter (-300). */
        const val ORDER = -350
        private const val MERGE_ROUTE = "cart-merge"

        fun browserMode(request: ServerHttpRequest): Boolean =
            request.headers.getFirst(BROWSER_SESSION_HEADER) == BROWSER_SESSION_COOKIE_MODE

        fun cartCookies(request: ServerHttpRequest): CookiePair =
            CookiePair(
                plain =
                    request.cookies
                        .getFirst(CookieName.CART.value)
                        ?.value
                        ?.takeIf(String::isNotEmpty),
                host =
                    request.cookies
                        .getFirst(CookieName.HOST_CART.value)
                        ?.value
                        ?.takeIf(String::isNotEmpty),
            )

        private fun withToken(
            exchange: ServerWebExchange,
            token: String,
        ): ServerWebExchange =
            exchange
                .mutate()
                .request(
                    exchange.request
                        .mutate()
                        .header(CART_TOKEN_HEADER, token)
                        .build(),
                ).build()
    }
}

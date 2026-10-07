package com.ecommerce.gateway.routing

import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import com.ecommerce.gateway.web.EdgeHeaders
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.security.Principal

/** Exchange attribute holding the [Caller] of an authenticated request (absent for anonymous calls). */
const val CALLER_ATTRIBUTE = "com.ecommerce.gateway.caller"

/**
 * Exchange attribute holding the [JwtAuthenticationToken] of a bearer the browser-session filter unsealed from the
 * session cookie and verified; it replaces the (anonymous) principal the resource server saw before the cookie was
 * read.
 */
const val BROWSER_AUTHENTICATION_ATTRIBUTE = "com.ecommerce.gateway.browserAuthentication"

/**
 * Applies the auth requirement the matched route declares (401 without a token, 403 without the role), then
 * forwards the validated identity as `X-Account-Id` and `X-Roles` (client copies were dropped at the edge) and marks
 * responses of authenticated calls `Cache-Control: no-store`. The bearer token itself is forwarded too: every
 * service validates it again (deny by default, FR-005).
 */
@Component
class RouteAccessFilter(
    private val policies: RoutePolicies,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val policy = policies.of(exchange) ?: return chain.filter(exchange)
        val fromCookie = exchange.getAttribute<Principal>(BROWSER_AUTHENTICATION_ATTRIBUTE)
        val principal = fromCookie?.let { Mono.just(it) } ?: exchange.getPrincipal()
        return principal
            .mapNotNull { principal -> (principal as? JwtAuthenticationToken)?.let(::callerOf) }
            .map { listOf(it) }
            .defaultIfEmpty(emptyList())
            .flatMap { callers -> admit(exchange, chain, policy, callers.firstOrNull()) }
    }

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    private fun admit(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
        policy: RoutePolicy,
        caller: Caller?,
    ): Mono<Void> {
        if (policy.auth != AuthRequirement.ANONYMOUS || caller != null) noStore(exchange)
        return when (policy.auth.decide(caller)) {
            AccessDecision.UNAUTHENTICATED -> {
                Mono.error(GatewayProblemException(GatewayProblem.UNAUTHORIZED))
            }

            AccessDecision.FORBIDDEN -> {
                Mono.error(GatewayProblemException(GatewayProblem.FORBIDDEN))
            }

            AccessDecision.GRANTED -> {
                if (caller == null) {
                    chain.filter(exchange)
                } else {
                    exchange.attributes[CALLER_ATTRIBUTE] = caller
                    chain.filter(withIdentity(exchange, caller))
                }
            }
        }
    }

    private fun noStore(exchange: ServerWebExchange) {
        val response = exchange.response
        response.beforeCommit {
            response.headers.set(HttpHeaders.CACHE_CONTROL, EdgeHeaders.NO_STORE)
            response.headers.remove(HttpHeaders.EXPIRES)
            Mono.empty()
        }
    }

    private fun withIdentity(
        exchange: ServerWebExchange,
        caller: Caller,
    ): ServerWebExchange =
        exchange
            .mutate()
            .request { request ->
                request.headers { headers ->
                    headers.set(EdgeHeaders.ACCOUNT_ID, caller.accountId)
                    headers.set(EdgeHeaders.ROLES, caller.roles.sorted().joinToString(","))
                }
            }.build()

    private fun callerOf(token: JwtAuthenticationToken): Caller? =
        token.token.subject?.let { subject ->
            Caller(
                accountId = subject,
                roles =
                    token.token
                        .getClaimAsStringList(ROLES_CLAIM)
                        .orEmpty()
                        .toSet(),
            )
        }

    private companion object {
        /** Before the route filters (Retry is order 1) and Spring Cloud Gateway's routing filters. */
        const val ORDER = -300
        const val ROLES_CLAIM = "roles"
    }
}

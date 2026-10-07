package com.ecommerce.gateway.browser

import com.ecommerce.gateway.correlation.CorrelationIds
import com.ecommerce.gateway.routing.BROWSER_AUTHENTICATION_ATTRIBUTE
import com.ecommerce.gateway.web.EdgeHeaders
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock

/** Request header selecting browser mode (`X-Browser-Session: cookie`). */
const val BROWSER_SESSION_HEADER = "X-Browser-Session"
const val BROWSER_SESSION_COOKIE_MODE = "cookie"

/**
 * The browser-session capability (gateway-browser-session.yaml, data-model.md section 4.3), first in the chain so
 * that the bearer it injects is validated and role-checked like any other
 * ([com.ecommerce.gateway.routing.RouteAccessFilter]):
 *
 * - no session cookie: the request passes unchanged (API clients keep their bearer tokens);
 * - a cookie next to `Authorization` is 400; a cross-site non-GET is 403; an unsealable, idle or doubled cookie is 401
 *   with the cookie deleted (sign-out answers 204 instead);
 * - a valid cookie: the access token is refreshed through identity when it expires within the refresh window, then
 *   authenticated ([BearerAuthenticator]) and injected as `Authorization: Bearer`; the response re-sets the cookie
 *   with a new `lastSeenAt`, or deletes it on sign-out and on an upstream 401 ([SessionCookieActions]);
 * - sign-in and refresh with `X-Browser-Session: cookie` answer `{expiresAt, roles}` and set the cookie
 *   ([SessionSummaryResponse]); the refresh token of a browser refresh comes from the cookie, never from the page.
 *
 * Cookie values, tokens and plaintext are never logged; every refusal is a platform `Problem` with
 * `Cache-Control: no-store` ([BrowserProblems]).
 */
class BrowserSessionFilter(
    private val sealer: SessionSealer,
    private val rules: BrowserSessionRules,
    private val refreshTokens: RefreshTokens,
    private val authenticator: BearerAuthenticator,
    private val clock: Clock,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val route =
            exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) ?: return chain.filter(exchange)
        val request = exchange.request
        val transport = Transport.of(request.uri.scheme)
        val browserMode = request.headers.getFirst(BROWSER_SESSION_HEADER) == BROWSER_SESSION_COOKIE_MODE
        return when (val kind = RouteKind.of(route.id, request.path.value())) {
            RouteKind.IGNORED -> chain.filter(exchange)
            RouteKind.SIGN_IN -> chain.filter(if (browserMode) signingIn(exchange, transport) else exchange)
            else -> decide(exchange, chain, kind, transport, browserMode)
        }
    }

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    private fun decide(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
        kind: RouteKind,
        transport: Transport,
        browserMode: Boolean,
    ): Mono<Void> {
        val cookies = sessionCookies(exchange.request)
        val browserRefresh = kind == RouteKind.REFRESH && browserMode
        return when (
            val decision =
                rules.decide(
                    browserRequest(exchange.request, cookies),
                    clock.instant(),
                    sealer::unseal,
                )
        ) {
            BrowserSessionDecision.PassThrough -> {
                if (browserRefresh) {
                    Mono.error(BrowserProblems.sessionExpired(listOf(BrowserCookies.delete(transport.session))))
                } else {
                    chain.filter(exchange)
                }
            }

            BrowserSessionDecision.BothCredentials -> {
                Mono.error(BrowserProblems.bothCredentials())
            }

            BrowserSessionDecision.CrossSite -> {
                Mono.error(BrowserProblems.crossSite())
            }

            is BrowserSessionDecision.Invalid -> {
                refuse(exchange, kind, deletionsFor(decision.reason, cookies))
            }

            is BrowserSessionDecision.Authenticated -> {
                val arrivedAs = requireNotNull(cookies.nameOf(CookieKind.SESSION)) { "a valid session has one name" }
                val context = CookieContext(kind, transport, arrivedAs)
                if (browserRefresh) {
                    chain.filter(refreshing(exchange, context, decision.session))
                } else {
                    forward(exchange, chain, context, decision)
                }
            }
        }
    }

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    private fun forward(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
        context: CookieContext,
        decision: BrowserSessionDecision.Authenticated,
    ): Mono<Void> {
        val now = clock.instant()
        val correlationId = exchange.request.headers.getFirst(CorrelationIds.HEADER)
        val current =
            if (decision.refreshFirst) {
                refreshed(decision.session, correlationId, context.arrivedAs)
            } else {
                Mono.just(decision.session)
            }
        return current.flatMap { session ->
            authenticator.authenticate(session.accessToken, context.arrivedAs).flatMap { authentication ->
                SessionCookieActions.onResponse(exchange.response, context, session.seenAt(now), sealer)
                exchange.attributes[BROWSER_AUTHENTICATION_ATTRIBUTE] = authentication
                chain.filter(exchange.mutate().request(withBearer(exchange.request, session.accessToken)).build())
            }
        }
    }

    private fun refreshed(
        session: SealedSession,
        correlationId: String?,
        arrivedAs: CookieName,
    ): Mono<SealedSession> =
        refreshTokens.refresh(session.refreshToken, correlationId).flatMap { outcome ->
            when (outcome) {
                is RefreshOutcome.Rotated -> {
                    Mono.just(session.rotated(outcome.tokens))
                }

                RefreshOutcome.Refused -> {
                    Mono.error(BrowserProblems.sessionExpired(listOf(BrowserCookies.delete(arrivedAs))))
                }

                is RefreshOutcome.Unavailable -> {
                    Mono.error(BrowserProblems.refreshUnavailable(outcome.cause))
                }
            }
        }

    /** An unusable cookie: 401 problem with the deletion(s), except on sign-out, which is 204 with them. */
    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    private fun refuse(
        exchange: ServerWebExchange,
        kind: RouteKind,
        deletions: List<String>,
    ): Mono<Void> {
        if (kind != RouteKind.SIGN_OUT) return Mono.error(BrowserProblems.sessionExpired(deletions))
        val response = exchange.response
        response.statusCode = HttpStatus.NO_CONTENT
        deletions.forEach { response.headers.add(HttpHeaders.SET_COOKIE, it) }
        response.headers.set(HttpHeaders.CACHE_CONTROL, EdgeHeaders.NO_STORE)
        return response.setComplete()
    }

    /** Sign-in in browser mode: identity's 200 token pair becomes a new sealed session and a summary body. */
    private fun signingIn(
        exchange: ServerWebExchange,
        transport: Transport,
    ): ServerWebExchange {
        val response =
            SessionSummaryResponse(exchange.response, transport, sealer, rules.idleTimeout) { tokens, claims ->
                SealedSession.issued(tokens, claims, clock.instant())
            }
        return exchange.mutate().response(response).build()
    }

    /** Refresh in browser mode: the body is identity's `RefreshRequest` built from the cookie; 200 renews it. */
    private fun refreshing(
        exchange: ServerWebExchange,
        context: CookieContext,
        session: SealedSession,
    ): ServerWebExchange {
        val body = SessionJson.refreshRequest(session.refreshToken)
        val withHeaders =
            exchange
                .mutate()
                .request { request ->
                    request.headers { headers ->
                        headers.contentType = MediaType.APPLICATION_JSON
                        headers.contentLength = body.size.toLong()
                        headers.remove(HttpHeaders.TRANSFER_ENCODING)
                        headers.remove(HttpHeaders.CONTENT_ENCODING)
                    }
                }.build()
        val request =
            object : ServerHttpRequestDecorator(withHeaders.request) {
                override fun getBody() = Flux.just(exchange.response.bufferFactory().wrap(body))
            }
        val response =
            SessionSummaryResponse(exchange.response, context.transport, sealer, rules.idleTimeout) { tokens, _ ->
                session.rotated(tokens).seenAt(clock.instant())
            }
        SessionCookieActions.deleteOnUnauthorized(exchange.response, context.arrivedAs)
        return withHeaders
            .mutate()
            .request(request)
            .response(response)
            .build()
    }

    private fun deletionsFor(
        reason: InvalidSession,
        cookies: CookiePair,
    ): List<String> =
        if (reason == InvalidSession.BOTH_NAMES) {
            BrowserCookies.deleteBoth(CookieKind.SESSION)
        } else {
            listOfNotNull(cookies.nameOf(CookieKind.SESSION)?.let(BrowserCookies::delete))
        }

    companion object {
        /** Before [com.ecommerce.gateway.routing.RouteAccessFilter] (-300) and the rate limiter (-200). */
        const val ORDER = -400
        private const val SEC_FETCH_SITE = "Sec-Fetch-Site"

        fun sessionCookies(request: ServerHttpRequest): CookiePair =
            CookiePair(
                plain =
                    request.cookies
                        .getFirst(CookieName.SESSION.value)
                        ?.value
                        ?.takeIf(String::isNotEmpty),
                host =
                    request.cookies
                        .getFirst(CookieName.HOST_SESSION.value)
                        ?.value
                        ?.takeIf(String::isNotEmpty),
            )

        fun browserRequest(
            request: ServerHttpRequest,
            cookies: CookiePair,
        ): BrowserRequest =
            BrowserRequest(
                method = request.method.name(),
                hasAuthorization = !request.headers.getFirst(HttpHeaders.AUTHORIZATION).isNullOrBlank(),
                cookies = cookies,
                secFetchSite = request.headers.getFirst(SEC_FETCH_SITE),
                origin = request.headers.getFirst(HttpHeaders.ORIGIN),
                requestUri = request.uri,
            )

        private fun withBearer(
            request: ServerHttpRequest,
            accessToken: String,
        ): ServerHttpRequest = request.mutate().header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken").build()
    }
}

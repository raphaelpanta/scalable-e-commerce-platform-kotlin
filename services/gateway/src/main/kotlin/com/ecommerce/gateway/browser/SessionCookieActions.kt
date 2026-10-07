package com.ecommerce.gateway.browser

import com.ecommerce.gateway.web.EdgeHeaders
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import reactor.core.publisher.Mono

/** The session cookie of one request: the route kind, how the request arrived and under which name the cookie came. */
data class CookieContext(
    val kind: RouteKind,
    val transport: Transport,
    val arrivedAs: CookieName,
)

/**
 * What happens to the session cookie on the way back, decided when the response commits (its status is known then):
 * deleted on sign-out and on an upstream 401, otherwise re-set with the renewed session (silent renewal). When one
 * name is set the other is deleted in the same response. Every such response is `Cache-Control: no-store`.
 */
object SessionCookieActions {
    private val UNAUTHORIZED = HttpStatus.UNAUTHORIZED.value()

    fun onResponse(
        response: ServerHttpResponse,
        context: CookieContext,
        renewed: SealedSession,
        sealer: SessionSealer,
    ) {
        response.beforeCommit {
            val headers = response.headers
            if (context.kind == RouteKind.SIGN_OUT || response.statusCode?.value() == UNAUTHORIZED) {
                headers.add(HttpHeaders.SET_COOKIE, BrowserCookies.delete(context.arrivedAs))
            } else {
                // Over the 4 KiB budget nothing is set: the browser keeps the cookie it sent (fail closed, never
                // a truncated value).
                BrowserCookies.set(context.transport.session, sealer.seal(renewed))?.let { cookie ->
                    headers.add(HttpHeaders.SET_COOKIE, cookie)
                    headers.add(HttpHeaders.SET_COOKIE, BrowserCookies.delete(context.transport.session.counterpart))
                }
            }
            headers.set(HttpHeaders.CACHE_CONTROL, EdgeHeaders.NO_STORE)
            Mono.empty()
        }
    }

    /** A browser refresh whose rotation identity refused (401 passed through) ends the session. */
    fun deleteOnUnauthorized(
        response: ServerHttpResponse,
        arrivedAs: CookieName,
    ) {
        response.beforeCommit {
            if (response.statusCode?.value() == UNAUTHORIZED) {
                response.headers.add(HttpHeaders.SET_COOKIE, BrowserCookies.delete(arrivedAs))
                response.headers.set(HttpHeaders.CACHE_CONTROL, EdgeHeaders.NO_STORE)
            }
            Mono.empty()
        }
    }
}

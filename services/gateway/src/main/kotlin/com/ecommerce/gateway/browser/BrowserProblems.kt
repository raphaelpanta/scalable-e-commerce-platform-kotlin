package com.ecommerce.gateway.browser

import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import com.ecommerce.gateway.web.EdgeHeaders
import org.springframework.http.HttpHeaders

/**
 * The refusals of the browser-session filters (gateway-browser-session.yaml, `components.responses`): platform
 * `Problem` answers with fixed details, `Cache-Control: no-store`, and the cookie deletions that end a session.
 */
object BrowserProblems {
    private const val BOTH_CREDENTIALS =
        "A request must carry either the session cookie or an Authorization header, not both."
    private const val CROSS_SITE = "Cross-site requests are not allowed."
    private const val SESSION_EXPIRED = "The session has expired. Sign in again."
    private const val REFRESH_UNAVAILABLE = "The session could not be renewed right now. Try again later."
    private const val TOKENS_UNVERIFIABLE = "Access tokens cannot be verified right now. Try again later."
    private const val UNUSABLE_TOKENS = "The identity service returned an unusable token pair."
    private const val TOO_LARGE = "The session does not fit the cookie budget."
    private val NO_STORE = mapOf(HttpHeaders.CACHE_CONTROL to EdgeHeaders.NO_STORE)

    /** 400 `validation`: cookie and `Authorization` together; nothing changes. */
    fun bothCredentials() = GatewayProblemException(GatewayProblem.BAD_REQUEST, BOTH_CREDENTIALS, NO_STORE)

    /** 403 `forbidden`: a non-GET request from another site; the cookie is left alone. */
    fun crossSite() = GatewayProblemException(GatewayProblem.FORBIDDEN, CROSS_SITE, NO_STORE)

    /** 401 `unauthorized` with the cookie [deletions]: idle, unsealable, doubled, refused or invalid session. */
    fun sessionExpired(deletions: List<String>) =
        GatewayProblemException(GatewayProblem.UNAUTHORIZED, SESSION_EXPIRED, NO_STORE, deletions)

    /** 503 `unavailable`: identity could not rotate the tokens; the cookie is kept for a retry. */
    fun refreshUnavailable(cause: Throwable?) =
        GatewayProblemException(GatewayProblem.UNAVAILABLE, REFRESH_UNAVAILABLE, NO_STORE, cause = cause)

    /** 503 `unavailable`: the signing keys cannot be read, so the unsealed bearer cannot be verified. */
    fun tokensUnverifiable(cause: Throwable?) =
        GatewayProblemException(GatewayProblem.UNAVAILABLE, TOKENS_UNVERIFIABLE, NO_STORE, cause = cause)

    /** 502 `unavailable`: identity's 200 body is not a token pair the gateway can seal. */
    fun unusableTokens() = GatewayProblemException(GatewayProblem.BAD_GATEWAY, UNUSABLE_TOKENS, NO_STORE)

    /** 502 `unavailable`: the sealed session would exceed the cookie budget (fail closed, no truncation). */
    fun sessionTooLarge() = GatewayProblemException(GatewayProblem.BAD_GATEWAY, TOO_LARGE, NO_STORE)
}

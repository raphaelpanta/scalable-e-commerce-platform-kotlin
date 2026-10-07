package com.ecommerce.gateway.browser

import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.Instant

/** What the browser-session rules do with a matched route (gateway-browser-session.yaml, "Session rules"). */
enum class RouteKind {
    /** The cookie is left untouched: registration, password resets, the storefront shell, telemetry. */
    IGNORED,

    /** `POST /api/v1/identity/sessions`: an existing cookie is ignored and replaced on success. */
    SIGN_IN,

    /** `POST /api/v1/identity/sessions/refresh`: in browser mode the refresh token comes from the cookie. */
    REFRESH,

    /** `DELETE /api/v1/identity/sessions/current`: the cookie is deleted whatever happens. */
    SIGN_OUT,

    /** Every other API route: the decision table applies. */
    API,
    ;

    companion object {
        private val ignoredRoutes = setOf("identity-registration", "storefront", "telemetry-traces", "telemetry-logs")
        private const val CREDENTIALS_ROUTE = "identity-credentials"
        private const val SIGN_OUT_ROUTE = "identity-sign-out"
        private const val SIGN_IN_PATH = "/api/v1/identity/sessions"
        private const val REFRESH_PATH = "/api/v1/identity/sessions/refresh"

        fun of(
            routeId: String,
            path: String,
        ): RouteKind =
            when {
                routeId in ignoredRoutes -> IGNORED
                routeId == SIGN_OUT_ROUTE -> SIGN_OUT
                routeId != CREDENTIALS_ROUTE -> API
                path == SIGN_IN_PATH -> SIGN_IN
                path == REFRESH_PATH -> REFRESH
                else -> IGNORED
            }
    }
}

/** The parts of a request the decision table reads (data-model.md section 4.3). */
data class BrowserRequest(
    val method: String,
    val hasAuthorization: Boolean,
    val cookies: CookiePair,
    val secFetchSite: String?,
    val origin: String?,
    /** Scheme, host and port the request was addressed to. */
    val requestUri: URI,
) {
    val safeMethod: Boolean get() = method.uppercase() in SAFE_METHODS

    private companion object {
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}

/** Why a session cookie is refused with 401 and deleted. */
enum class InvalidSession { BOTH_NAMES, UNSEALABLE, IDLE }

/** The outcome of the decision table for one request. */
sealed interface BrowserSessionDecision {
    /** Rows 1 and 2: no cookie; the request is forwarded unchanged. */
    data object PassThrough : BrowserSessionDecision

    /** Row 3: cookie and `Authorization` together (400 `validation`, cookie untouched). */
    data object BothCredentials : BrowserSessionDecision

    /** Row 4: a non-GET request from another site (403 `forbidden`, cookie untouched). */
    data object CrossSite : BrowserSessionDecision

    /** Row 5: no usable session (401 `unauthorized`, the cookie deleted). */
    data class Invalid(
        val reason: InvalidSession,
    ) : BrowserSessionDecision

    /** Row 6: a valid session; [refreshFirst] when its access token expires within the refresh window. */
    data class Authenticated(
        val session: SealedSession,
        val refreshFirst: Boolean,
    ) : BrowserSessionDecision
}

/** The decision table of data-model.md section 4.3, evaluated in row order; pure. */
class BrowserSessionRules(
    val idleTimeout: Duration,
    val refreshAhead: Duration,
) {
    fun decide(
        request: BrowserRequest,
        now: Instant,
        unseal: (String) -> SealedSession?,
    ): BrowserSessionDecision =
        when {
            !request.cookies.present -> {
                BrowserSessionDecision.PassThrough
            }

            request.hasAuthorization -> {
                BrowserSessionDecision.BothCredentials
            }

            !request.safeMethod && !sameOrigin(request) -> {
                BrowserSessionDecision.CrossSite
            }

            else -> {
                request.cookies.single?.let { value -> validate(unseal(value), now) }
                    ?: BrowserSessionDecision.Invalid(InvalidSession.BOTH_NAMES)
            }
        }

    private fun validate(
        session: SealedSession?,
        now: Instant,
    ): BrowserSessionDecision =
        when {
            session == null -> {
                BrowserSessionDecision.Invalid(InvalidSession.UNSEALABLE)
            }

            session.isIdle(now, idleTimeout) -> {
                BrowserSessionDecision.Invalid(InvalidSession.IDLE)
            }

            else -> {
                val claims = AccessTokenClaims.parse(session.accessToken)
                BrowserSessionDecision.Authenticated(session, claims?.needsRefresh(now, refreshAhead) ?: true)
            }
        }

    /**
     * The cross-site check: `Sec-Fetch-Site` must be `same-origin` or `none`, and `Origin`, when present, must equal
     * the request's own origin. Without `Sec-Fetch-Site` the check fails closed unless a matching `Origin` is present
     * (plan.md, "Missing Sec-Fetch-Site").
     */
    fun sameOrigin(request: BrowserRequest): Boolean {
        val originOk = request.origin?.let { originMatches(it, request.requestUri) }
        return when (request.secFetchSite?.lowercase()) {
            null -> originOk == true
            in ALLOWED_FETCH_SITES -> originOk != false
            else -> false
        }
    }

    companion object {
        private val ALLOWED_FETCH_SITES = setOf("same-origin", "none")
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80

        /** Scheme, host (case-insensitively) and effective port of [origin] equal the request's. */
        fun originMatches(
            origin: String,
            requestUri: URI,
        ): Boolean {
            val candidate = parse(origin.trim()) ?: return false
            val scheme = candidate.scheme?.lowercase()
            val host = candidate.host?.lowercase()
            return scheme != null &&
                host != null &&
                scheme == requestUri.scheme?.lowercase() &&
                host == requestUri.host?.lowercase() &&
                effectivePort(candidate) == effectivePort(requestUri)
        }

        private fun parse(origin: String): URI? =
            try {
                URI(origin)
            } catch (_: URISyntaxException) {
                null
            }

        private fun effectivePort(uri: URI): Int =
            when {
                uri.port != -1 -> uri.port
                uri.scheme.equals(Transport.HTTPS_SCHEME, ignoreCase = true) -> HTTPS_PORT
                else -> HTTP_PORT
            }
    }
}

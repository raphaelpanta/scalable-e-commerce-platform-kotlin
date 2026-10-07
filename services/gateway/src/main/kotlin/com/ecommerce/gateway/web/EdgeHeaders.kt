package com.ecommerce.gateway.web

import org.springframework.http.HttpHeaders

/** Header rules at the edge (contracts/gateway-routes.md, "JWT validation" and "Security headers"). */
object EdgeHeaders {
    const val ACCOUNT_ID = "X-Account-Id"
    const val ROLES = "X-Roles"
    const val INTERNAL_TOKEN = "X-Internal-Token"

    /** Headers only the gateway (or the platform network) may set; client-supplied copies are dropped. */
    val CLIENT_FORBIDDEN: List<String> =
        listOf(
            ACCOUNT_ID,
            ROLES,
            INTERNAL_TOKEN,
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Port",
            "X-Forwarded-Prefix",
            "X-Real-IP",
        )

    /** Headers that never leave the gateway in a response: server details and internal plumbing. */
    val RESPONSE_FORBIDDEN: List<String> =
        listOf("Server", "X-Powered-By", "X-Application-Context", ACCOUNT_ID, ROLES, INTERNAL_TOKEN)

    /** Security headers on every response, gateway-generated errors included. */
    val SECURITY: Map<String, String> =
        linkedMapOf(
            "Strict-Transport-Security" to "max-age=31536000; includeSubDomains",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            CONTENT_SECURITY_POLICY to API_CSP,
            "Referrer-Policy" to "no-referrer",
        )

    const val NO_STORE = "no-store"

    /** The route id of the storefront shell (feature 005), the only route with a page policy instead of the API one. */
    const val STOREFRONT_ROUTE = "storefront"
    const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
    const val PERMISSIONS_POLICY = "Permissions-Policy"

    /** The API policy: nothing may load, embed or submit (every API response and gateway error). */
    const val API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

    /**
     * The storefront policy (research.md section 6): same-origin scripts, styles and fonts without `unsafe-inline`,
     * external HTTPS product images, no framing, no plugins.
     */
    const val STOREFRONT_CSP =
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; " +
            "font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"
    const val STOREFRONT_PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()"

    /** Headers the storefront route replaces or adds on top of [SECURITY]. */
    val STOREFRONT: Map<String, String> =
        linkedMapOf(
            CONTENT_SECURITY_POLICY to STOREFRONT_CSP,
            PERMISSIONS_POLICY to STOREFRONT_PERMISSIONS_POLICY,
        )

    fun stripClientSupplied(headers: HttpHeaders) {
        CLIENT_FORBIDDEN.forEach(headers::remove)
    }

    fun harden(headers: HttpHeaders) {
        RESPONSE_FORBIDDEN.forEach(headers::remove)
        SECURITY.forEach(headers::set)
    }

    /** The extra or replacing headers of [routeId]: the storefront policy on the shell route, nothing elsewhere. */
    fun policyFor(routeId: String?): Map<String, String> = if (routeId == STOREFRONT_ROUTE) STOREFRONT else emptyMap()

    /** Applies [policyFor] after [harden]: the storefront CSP replaces the API one, the API routes keep it. */
    fun applyRoutePolicy(
        headers: HttpHeaders,
        routeId: String?,
    ) {
        policyFor(routeId).forEach(headers::set)
    }
}

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
            "Content-Security-Policy" to
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            "Referrer-Policy" to "no-referrer",
        )

    const val NO_STORE = "no-store"

    fun stripClientSupplied(headers: HttpHeaders) {
        CLIENT_FORBIDDEN.forEach(headers::remove)
    }

    fun harden(headers: HttpHeaders) {
        RESPONSE_FORBIDDEN.forEach(headers::remove)
        SECURITY.forEach(headers::set)
    }
}

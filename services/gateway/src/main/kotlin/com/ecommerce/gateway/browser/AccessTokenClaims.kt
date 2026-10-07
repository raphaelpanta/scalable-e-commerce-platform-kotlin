package com.ecommerce.gateway.browser

import com.ecommerce.gateway.browser.BrowserJson.text
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The claims of an access token the gateway needs for the session: `sub`, `roles` and `exp`. Read from the JWT
 * payload without verifying the signature: the token came straight from identity (sign-in or refresh) or out of a
 * sealed cookie, and [BearerAuthenticator] verifies it again before any route is reached.
 */
data class AccessTokenClaims(
    val subject: String,
    val roles: Set<String>,
    val expiresAt: Instant,
) {
    /** The token is refreshed ahead of time when it expires within [refreshAhead] of [now] (data-model.md 4.1). */
    fun needsRefresh(
        now: Instant,
        refreshAhead: Duration,
    ): Boolean = !expiresAt.isAfter(now.plus(refreshAhead))

    companion object {
        private const val SEGMENTS = 3
        private const val PAYLOAD = 1
        private const val SUBJECT = "sub"
        private const val EXPIRY = "exp"
        private const val ROLES = "roles"

        /** The claims of a compact JWS, or null when the token is not one or lacks `sub` or `exp`. */
        fun parse(token: String): AccessTokenClaims? {
            val payload = token.split('.').takeIf { it.size == SEGMENTS }?.let { payloadOf(it[PAYLOAD]) } ?: return null
            val subject = payload.text(SUBJECT)
            val expiry = payload.get(EXPIRY)?.takeIf { it.isNumber }?.longValue()
            return if (subject == null || expiry == null) {
                null
            } else {
                AccessTokenClaims(subject, roles(payload.get(ROLES)), Instant.ofEpochSecond(expiry))
            }
        }

        private fun payloadOf(segment: String): JsonNode? =
            try {
                BrowserJson.readObject(Base64.getUrlDecoder().decode(segment))
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun roles(node: JsonNode?): Set<String> =
            node
                ?.takeIf { it.isArray }
                ?.values()
                ?.filter { it.isString }
                ?.map { it.stringValue() }
                ?.toSet()
                .orEmpty()
    }
}

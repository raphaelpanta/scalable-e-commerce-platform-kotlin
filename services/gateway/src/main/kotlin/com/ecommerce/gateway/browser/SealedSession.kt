package com.ecommerce.gateway.browser

import java.time.Duration
import java.time.Instant

/**
 * The browser session the gateway seals into the session cookie (data-model.md section 4.1): the identity token pair,
 * the account and its roles, and the activity instants that drive the idle rule. A pure value; sealing is an adapter
 * around it ([SessionSealer]). `toString()` never shows the tokens.
 */
data class SealedSession(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val roles: Set<String>,
    val lastSeenAt: Instant,
    val issuedAt: Instant,
) {
    /** The session has been idle when [now] is past `lastSeenAt + idleTimeout` (older than the idle window). */
    fun isIdle(
        now: Instant,
        idleTimeout: Duration,
    ): Boolean = now.isAfter(expiresAt(idleTimeout))

    /** The instant the session ends without further activity: `lastSeenAt` plus the idle window. */
    fun expiresAt(idleTimeout: Duration): Instant = lastSeenAt.plus(idleTimeout)

    /** The session after a request used it at [now] (silent renewal). */
    fun seenAt(now: Instant): SealedSession = copy(lastSeenAt = now)

    /** The session after identity rotated its tokens; account and roles follow the new access token when readable. */
    fun rotated(tokens: TokenPair): SealedSession {
        val claims = AccessTokenClaims.parse(tokens.accessToken)
        return copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            accountId = claims?.subject ?: accountId,
            roles = claims?.roles ?: roles,
        )
    }

    override fun toString(): String = "SealedSession(roles=$roles, lastSeenAt=$lastSeenAt, issuedAt=$issuedAt)"

    companion object {
        /** A new session from the token pair identity issued at [now]; identity is read from the access token. */
        fun issued(
            tokens: TokenPair,
            claims: AccessTokenClaims,
            now: Instant,
        ): SealedSession =
            SealedSession(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accountId = claims.subject,
                roles = claims.roles,
                lastSeenAt = now,
                issuedAt = now,
            )
    }
}

/** The token pair of identity's `TokenPair` (the members the gateway keeps). `toString()` shows nothing. */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
) {
    override fun toString(): String = "TokenPair(…)"
}

/**
 * Seals sessions and cart tokens into opaque cookie values and unseals them. An unseal failure of any kind (bad tag,
 * unknown key id, malformed value, unreadable payload) is `null`: the caller treats it as "no session".
 */
interface SessionSealer {
    fun seal(session: SealedSession): String

    fun unseal(value: String): SealedSession?

    fun sealToken(token: String): String

    fun unsealToken(value: String): String?
}

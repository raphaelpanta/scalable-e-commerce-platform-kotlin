package com.ecommerce.gateway.browser

import java.time.Duration

/**
 * How the request arrived, which decides the cookie names (gateway-routes.md "Cookie names by transport"): `__Host-`
 * prefixed names with `Secure` over HTTPS (scheme `https`, or `X-Forwarded-Proto: https` at the edge), the plain
 * names without `Secure` over plain HTTP such as `http://localhost`.
 */
enum class Transport(
    val session: CookieName,
    val cart: CookieName,
) {
    HTTPS(CookieName.HOST_SESSION, CookieName.HOST_CART),
    PLAIN(CookieName.SESSION, CookieName.CART),
    ;

    companion object {
        const val HTTPS_SCHEME = "https"
        const val FORWARDED_PROTO = "X-Forwarded-Proto"

        fun of(
            scheme: String?,
            forwardedProto: String? = null,
        ): Transport =
            if (scheme.equals(HTTPS_SCHEME, ignoreCase = true) ||
                forwardedProto?.substringBefore(',')?.trim().equals(HTTPS_SCHEME, ignoreCase = true)
            ) {
                HTTPS
            } else {
                PLAIN
            }
    }
}

/** The four cookie names; the `__Host-` names always carry `Secure`, the plain ones never. */
enum class CookieName(
    val value: String,
    val secure: Boolean,
    val kind: CookieKind,
) {
    HOST_SESSION("__Host-session", true, CookieKind.SESSION),
    SESSION("session", false, CookieKind.SESSION),
    HOST_CART("__Host-cart", true, CookieKind.CART),
    CART("cart", false, CookieKind.CART),
    ;

    /** The other name of the same kind: deleted whenever this one is set. */
    val counterpart: CookieName get() = entries.single { it.kind == kind && it != this }

    companion object {
        fun of(value: String): CookieName? = entries.firstOrNull { it.value == value }
    }
}

/** The two cookies and their fixed attributes (gateway-browser-session.yaml, cookie table). */
enum class CookieKind(
    val sameSite: String,
) {
    SESSION("Strict"),
    CART("Lax"),
}

/** The two values of one cookie kind as a request may carry them: the plain name, the `__Host-` name, or both. */
data class CookiePair(
    val plain: String?,
    val host: String?,
) {
    val both: Boolean get() = plain != null && host != null
    val present: Boolean get() = plain != null || host != null

    /** The single value present, or null when none or both are. */
    val single: String? get() = if (both) null else plain ?: host

    /** The name the single value arrived under (null when none or both). */
    fun nameOf(kind: CookieKind): CookieName? =
        when {
            both || !present -> null
            plain != null -> CookieName.entries.single { it.kind == kind && !it.secure }
            else -> CookieName.entries.single { it.kind == kind && it.secure }
        }

    companion object {
        val NONE = CookiePair(null, null)
    }
}

/**
 * `Set-Cookie` header values in the documented attribute order. A set value is refused (null) when the whole header
 * would exceed [MAX_COOKIE_BYTES]: the caller fails closed rather than sending a truncated cookie.
 */
object BrowserCookies {
    /** Whole cookie (name, value and attributes) budget, data-model.md section 4.1. */
    const val MAX_COOKIE_BYTES = 4096

    /** `name=value; HttpOnly[; Secure]; SameSite=...; Path=/[; Max-Age=n]`, or null over budget. */
    fun set(
        name: CookieName,
        value: String,
        maxAge: Duration? = null,
    ): String? {
        val header = "${name.value}=$value; ${attributes(name)}" + maxAge?.let { "; Max-Age=${it.seconds}" }.orEmpty()
        return header.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_COOKIE_BYTES }
    }

    /** `name=; Max-Age=0; HttpOnly[; Secure]; SameSite=...; Path=/`: the browser drops the cookie. */
    fun delete(name: CookieName): String = "${name.value}=; Max-Age=0; ${attributes(name)}"

    /** Deletion of both names of [kind] (a request that carried both). */
    fun deleteBoth(kind: CookieKind): List<String> = CookieName.entries.filter { it.kind == kind }.map(::delete)

    private fun attributes(name: CookieName): String =
        listOfNotNull("HttpOnly", "Secure".takeIf { name.secure }, "SameSite=${name.kind.sameSite}", "Path=/")
            .joinToString("; ")
}

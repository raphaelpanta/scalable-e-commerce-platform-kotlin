package com.ecommerce.gateway.browser

import com.ecommerce.gateway.security.UNIT_SUBJECT
import com.ecommerce.gateway.security.UnitSigningKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.net.URI
import java.time.Duration
import java.time.Instant

private const val IDLE_MINUTES = 30L
private const val REFRESH_AHEAD_SECONDS = 60L
private const val WINDOW_SECONDS = 3_600L
private const val VALID = "valid-cookie"
private const val BROKEN = "broken-cookie"
private const val HOST = "shop.example"
private const val PORT = 8080
private const val OTHER_PORT = 9090
private val IDLE = Duration.ofMinutes(IDLE_MINUTES)
private val REFRESH_AHEAD = Duration.ofSeconds(REFRESH_AHEAD_SECONDS)
private val REQUEST_URI = URI("http://$HOST:$PORT/api/v1/cart")

private val methods = Arb.element("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE", "PATCH")
private val fetchSites = Arb.element("same-origin", "none", "same-site", "cross-site", "SAME-ORIGIN").orNull()
private val origins =
    Arb
        .element(
            "http://$HOST:$PORT",
            "HTTP://${HOST.uppercase()}:$PORT",
            "http://$HOST:$OTHER_PORT",
            "https://$HOST:$PORT",
            "http://evil.example:$PORT",
            "null",
        ).orNull()
private val cookiePairs =
    Arb.of(
        CookiePair.NONE,
        CookiePair(VALID, null),
        CookiePair(null, VALID),
        CookiePair(BROKEN, null),
        CookiePair(null, BROKEN),
        CookiePair(VALID, VALID),
        CookiePair(VALID, BROKEN),
    )

private fun request(
    method: String,
    authorization: Boolean,
    cookies: CookiePair,
    fetchSite: String?,
    origin: String?,
) = BrowserRequest(method, authorization, cookies, fetchSite, origin, REQUEST_URI)

/** The documented cross-site rule, written out independently of the implementation. */
private fun documentedOriginOk(
    fetchSite: String?,
    origin: String?,
): Boolean {
    val originMatches = origin?.let { it.equals("http://$HOST:$PORT", ignoreCase = true) }
    return when (fetchSite?.lowercase()) {
        null -> originMatches == true
        "same-origin", "none" -> originMatches != false
        else -> false
    }
}

/** T014: the session rules of data-model.md sections 4.1 to 4.3 as properties. */
class BrowserSessionRulesSpec :
    FunSpec({
        val rules = BrowserSessionRules(IDLE, REFRESH_AHEAD)

        test("a session is idle exactly when now is past lastSeenAt plus 30 minutes") {
            checkAll(SessionArbs.session, Arb.long(-WINDOW_SECONDS..WINDOW_SECONDS)) { session, offset ->
                val now = session.lastSeenAt.plusSeconds(offset)
                session.isIdle(now, IDLE) shouldBe (offset > IDLE.seconds)
                session.expiresAt(IDLE) shouldBe session.lastSeenAt.plus(IDLE)
                session.seenAt(now).lastSeenAt shouldBe now
            }
        }

        test("a refresh is needed exactly when the access token expires within 60 seconds") {
            checkAll(
                SessionArbs.instant,
                Arb.long(-WINDOW_SECONDS..WINDOW_SECONDS),
                SessionArbs.roles,
            ) { now, offset, roles ->
                val expiresAt = now.plusSeconds(offset)
                val claims = AccessTokenClaims.parse(SessionArbs.unsignedToken("sub", roles, expiresAt))
                claims shouldBe AccessTokenClaims("sub", roles, Instant.ofEpochSecond(expiresAt.epochSecond))
                claims?.needsRefresh(now, REFRESH_AHEAD) shouldBe (offset <= REFRESH_AHEAD_SECONDS)
            }
            listOf("", "a.b", "a.b.c", "a.!!.c", "a.e30.c", "a.W10.c").forEach {
                AccessTokenClaims.parse(it) shouldBe
                    null
            }
        }

        test("the decision table of section 4.3 is evaluated in row order") {
            checkAll(
                methods,
                Arb.boolean(),
                cookiePairs,
                fetchSites,
                origins,
                SessionArbs.session,
            ) { method, authorization, cookies, fetchSite, origin, base ->
                val now = base.lastSeenAt
                val session =
                    base.copy(
                        accessToken =
                            SessionArbs.unsignedToken(
                                base.accountId,
                                base.roles,
                                now.plusSeconds(WINDOW_SECONDS),
                            ),
                    )
                val unseal = { value: String -> session.takeIf { value == VALID } }
                val decision = rules.decide(request(method, authorization, cookies, fetchSite, origin), now, unseal)
                val safe = method in setOf("GET", "HEAD", "OPTIONS")
                val expected =
                    when {
                        !cookies.present -> BrowserSessionDecision.PassThrough
                        authorization -> BrowserSessionDecision.BothCredentials
                        !safe && !documentedOriginOk(fetchSite, origin) -> BrowserSessionDecision.CrossSite
                        cookies.both -> BrowserSessionDecision.Invalid(InvalidSession.BOTH_NAMES)
                        cookies.single == BROKEN -> BrowserSessionDecision.Invalid(InvalidSession.UNSEALABLE)
                        else -> BrowserSessionDecision.Authenticated(session, refreshFirst = false)
                    }
                decision shouldBe expected
            }
        }

        test("an idle session is refused after the origin check, and a short-lived token asks for a refresh first") {
            checkAll(
                SessionArbs.session,
                Arb.long(-WINDOW_SECONDS..WINDOW_SECONDS),
                Arb.long(-WINDOW_SECONDS..WINDOW_SECONDS),
            ) {
                base,
                idleOffset,
                expiryOffset,
                ->
                val now = base.lastSeenAt.plusSeconds(idleOffset)
                val session =
                    base.copy(
                        accessToken =
                            SessionArbs.unsignedToken(
                                base.accountId,
                                base.roles,
                                now.plusSeconds(expiryOffset),
                            ),
                    )
                val decision = rules.decide(request("GET", false, CookiePair(VALID, null), null, null), now) { session }
                if (idleOffset > IDLE.seconds) {
                    decision shouldBe BrowserSessionDecision.Invalid(InvalidSession.IDLE)
                } else {
                    decision shouldBe
                        BrowserSessionDecision.Authenticated(session, expiryOffset <= REFRESH_AHEAD_SECONDS)
                }
            }
            checkAll(SessionArbs.session) { unreadable ->
                rules
                    .decide(
                        request("GET", false, CookiePair(VALID, null), null, null),
                        unreadable.lastSeenAt,
                    ) { unreadable }
                    .shouldBeInstanceOf<BrowserSessionDecision.Authenticated>()
                    .refreshFirst shouldBe true
            }
        }

        test("GET, HEAD and OPTIONS are never origin-checked; every other method is, failing closed when absent") {
            checkAll(methods, fetchSites, origins) { method, fetchSite, origin ->
                val request = request(method, false, CookiePair(VALID, null), fetchSite, origin)
                val checked = method !in setOf("GET", "HEAD", "OPTIONS")
                rules.sameOrigin(request) shouldBe documentedOriginOk(fetchSite, origin)
                val decision = rules.decide(request, Instant.EPOCH) { null }
                (decision == BrowserSessionDecision.CrossSite) shouldBe
                    (checked && !documentedOriginOk(fetchSite, origin))
            }
        }

        test("an Origin matches when scheme, host and effective port equal the request's") {
            val https = URI("https://$HOST/api/v1/cart")
            BrowserSessionRules.originMatches("https://$HOST", https) shouldBe true
            BrowserSessionRules.originMatches("https://$HOST:443", https) shouldBe true
            BrowserSessionRules.originMatches("https://$HOST:8443", https) shouldBe false
            BrowserSessionRules.originMatches("http://$HOST", https) shouldBe false
            BrowserSessionRules.originMatches("http://$HOST", URI("http://$HOST:80/")) shouldBe true
            BrowserSessionRules.originMatches(" http://$HOST:$PORT ", REQUEST_URI) shouldBe true
            BrowserSessionRules.originMatches("http://$HOST", REQUEST_URI) shouldBe false
            checkAll(Arb.string()) { garbage -> BrowserSessionRules.originMatches(garbage, REQUEST_URI) shouldBe false }
        }

        test("cookie names follow the transport: __Host- names with Secure over HTTPS or X-Forwarded-Proto https") {
            val schemes = Arb.element("http", "https", "HTTPS", "ws").orNull()
            val forwarded = Arb.element("https", "HTTPS", "http", "https, http", " https ", "http, https").orNull()
            checkAll(schemes, forwarded) { scheme, proto ->
                val https =
                    scheme.equals("https", ignoreCase = true) ||
                        proto?.substringBefore(',')?.trim().equals("https", ignoreCase = true)
                val transport = Transport.of(scheme, proto)
                transport shouldBe if (https) Transport.HTTPS else Transport.PLAIN
                transport.session shouldBe if (https) CookieName.HOST_SESSION else CookieName.SESSION
                transport.cart shouldBe if (https) CookieName.HOST_CART else CookieName.CART
                transport.session.secure shouldBe https
                transport.cart.secure shouldBe https
            }
            CookieName.HOST_SESSION.counterpart shouldBe CookieName.SESSION
            CookieName.CART.counterpart shouldBe CookieName.HOST_CART
            CookieName.of("__Host-cart") shouldBe CookieName.HOST_CART
            CookieName.of("other") shouldBe null
        }

        test("set and delete headers carry the documented attributes in order; both names are deleted together") {
            BrowserCookies.set(CookieName.SESSION, "v") shouldBe "session=v; HttpOnly; SameSite=Strict; Path=/"
            BrowserCookies.set(CookieName.HOST_SESSION, "v") shouldBe
                "__Host-session=v; HttpOnly; Secure; SameSite=Strict; Path=/"
            BrowserCookies.set(CookieName.CART, "v", Duration.ofDays(IDLE_MINUTES)) shouldBe
                "cart=v; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000"
            BrowserCookies.set(CookieName.HOST_CART, "v", Duration.ofDays(IDLE_MINUTES)) shouldBe
                "__Host-cart=v; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=2592000"
            BrowserCookies.delete(CookieName.SESSION) shouldBe "session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/"
            BrowserCookies.delete(CookieName.HOST_CART) shouldBe
                "__Host-cart=; Max-Age=0; HttpOnly; Secure; SameSite=Lax; Path=/"
            BrowserCookies.deleteBoth(CookieKind.SESSION) shouldBe
                listOf(BrowserCookies.delete(CookieName.HOST_SESSION), BrowserCookies.delete(CookieName.SESSION))
            CookiePair(VALID, VALID).nameOf(CookieKind.SESSION) shouldBe null
            CookiePair(VALID, null).nameOf(CookieKind.CART) shouldBe CookieName.CART
            CookiePair(null, VALID).nameOf(CookieKind.SESSION) shouldBe CookieName.HOST_SESSION
            CookiePair.NONE.nameOf(CookieKind.SESSION) shouldBe null
        }

        test("route kinds: sign-in, refresh and sign-out are special, registration and resets are ignored") {
            RouteKind.of("identity-credentials", "/api/v1/identity/sessions") shouldBe RouteKind.SIGN_IN
            RouteKind.of("identity-credentials", "/api/v1/identity/sessions/refresh") shouldBe RouteKind.REFRESH
            RouteKind.of("identity-credentials", "/api/v1/identity/password-resets") shouldBe RouteKind.IGNORED
            RouteKind.of("identity-credentials", "/api/v1/identity/password-resets/complete") shouldBe RouteKind.IGNORED
            RouteKind.of("identity-registration", "/api/v1/identity/accounts") shouldBe RouteKind.IGNORED
            RouteKind.of("identity-sign-out", "/api/v1/identity/sessions/current") shouldBe RouteKind.SIGN_OUT
            listOf("storefront", "telemetry-traces", "telemetry-logs").forEach {
                RouteKind.of(it, "/x") shouldBe
                    RouteKind.IGNORED
            }
            checkAll(Arb.element("cart", "cart-merge", "order-placement", "identity-profile", "catalog-reads")) { id ->
                RouteKind.of(id, "/api/v1/anything") shouldBe RouteKind.API
            }
        }

        test("a rotated session takes the new tokens and the new token's identity when it is readable") {
            checkAll(
                SessionArbs.session,
                SessionArbs.token,
                SessionArbs.accountId,
                SessionArbs.roles,
            ) { session, refresh, account, roles ->
                val readable = SessionArbs.unsignedToken(account, roles, Instant.EPOCH)
                session.rotated(TokenPair(readable, refresh)) shouldBe
                    session.copy(accessToken = readable, refreshToken = refresh, accountId = account, roles = roles)
                session.rotated(TokenPair("opaque", refresh)) shouldBe
                    session.copy(accessToken = "opaque", refreshToken = refresh)
                SealedSession.issued(
                    TokenPair(readable, refresh),
                    AccessTokenClaims(account, roles, Instant.EPOCH),
                    Instant.EPOCH,
                ) shouldBe
                    SealedSession(readable, refresh, account, roles, Instant.EPOCH, Instant.EPOCH)
            }
        }

        test("the summary carries only expiresAt and sorted roles; identity bodies are read strictly") {
            val summary = String(SessionJson.summary(Instant.EPOCH, setOf("shopper", "operator")))
            summary shouldBe """{"expiresAt":"1970-01-01T00:00:00Z","roles":["operator","shopper"]}"""
            String(SessionJson.refreshRequest("r-1")) shouldBe """{"refreshToken":"r-1"}"""
            SessionJson.tokenPair(
                """{"accessToken":"a","refreshToken":"r","tokenType":"Bearer","expiresIn":900}""".toByteArray(),
            ) shouldBe
                TokenPair("a", "r")
            listOf("[]", "{}", """{"accessToken":"a"}""", """{"accessToken":1,"refreshToken":"r"}""", "nope").forEach {
                SessionJson.tokenPair(it.toByteArray()) shouldBe null
            }
        }

        test("the claims of a token signed like identity's are read: subject, roles and expiry") {
            val signed = UnitSigningKey().token()
            val claims = AccessTokenClaims.parse(signed).shouldNotBeNull()
            claims.subject shouldBe UNIT_SUBJECT
            claims.roles shouldBe setOf("shopper")
            claims.expiresAt.isAfter(Instant.now()) shouldBe true
            SessionJson.tokenPair("""{"accessToken":"$signed","refreshToken":"r"}""".toByteArray()) shouldBe
                TokenPair(signed, "r")
        }

        test("a safe method is GET, HEAD or OPTIONS in any case") {
            checkAll(methods, Arb.boolean()) { method, lowercase ->
                val spelled = if (lowercase) method.lowercase() else method
                request(spelled, false, CookiePair.NONE, null, null).safeMethod shouldBe
                    (method in setOf("GET", "HEAD", "OPTIONS"))
            }
        }
    })

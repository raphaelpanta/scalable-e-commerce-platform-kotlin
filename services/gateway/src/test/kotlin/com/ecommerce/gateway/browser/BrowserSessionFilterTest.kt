package com.ecommerce.gateway.browser

import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import com.ecommerce.gateway.routing.BROWSER_AUTHENTICATION_ATTRIBUTE
import com.ecommerce.gateway.routing.RecordingChain
import com.ecommerce.gateway.routing.exchange
import com.ecommerce.gateway.routing.metadata
import com.ecommerce.gateway.routing.route
import com.ecommerce.gateway.security.JwksUnavailableException
import com.ecommerce.gateway.security.SecurityConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec

private const val ACCOUNT = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
private const val IDLE_MINUTES = 30L
private const val REFRESH_AHEAD_SECONDS = 60L
private const val TOKEN_LIFETIME_MINUTES = 15L
private const val NEAR_EXPIRY_SECONDS = 30L
private const val IDLE_EXCEEDED_MINUTES = 31L
private val FAR_FUTURE: Instant = Instant.parse("2027-01-01T00:00:00Z")

/** Readable claims (so no refresh is attempted) that the faked decoder nevertheless rejects. */
private val BAD_TOKEN = SessionArbs.unsignedToken("bad", listOf("shopper"), FAR_FUTURE)
private val UNAVAILABLE_TOKEN = SessionArbs.unsignedToken("unverifiable", listOf("shopper"), FAR_FUTURE)
private val NOW: Instant = Instant.parse("2026-10-04T10:00:00Z")
private val IDLE = Duration.ofMinutes(IDLE_MINUTES)
private val REFRESH_AHEAD = Duration.ofSeconds(REFRESH_AHEAD_SECONDS)
private val SET_COOKIE = HttpHeaders.SET_COOKIE

private fun accessToken(
    expiresAt: Instant = NOW.plus(Duration.ofMinutes(TOKEN_LIFETIME_MINUTES)),
    roles: List<String> = listOf("shopper"),
) = SessionArbs.unsignedToken(ACCOUNT, roles, expiresAt)

private fun tokenPairBody(
    access: String,
    refresh: String,
) = """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresIn":900}"""

/** The resource server's decoder, faked: readable claims for every token except the two named failures. */
private val fakeDecoder =
    ReactiveJwtDecoder { token ->
        when (token) {
            BAD_TOKEN -> {
                Mono.error(BadJwtException("expired"))
            }

            UNAVAILABLE_TOKEN -> {
                Mono.error(JwksUnavailableException("no keys"))
            }

            else -> {
                val claims = AccessTokenClaims.parse(token).shouldNotBeNull()
                Mono.just(
                    Jwt
                        .withTokenValue(token)
                        .header("alg", "EdDSA")
                        .subject(claims.subject)
                        .claim("roles", claims.roles.toList())
                        .expiresAt(claims.expiresAt)
                        .build(),
                )
            }
        }
    }

/** Unit layer of the session filter: the decision table wired to the exchange, cookies and refresh port. */
@Suppress("LargeClass") // one test per row of the decision table and per cookie outcome
class BrowserSessionFilterTest :
    FunSpec({
        val sealer =
            AesGcmSessionSealer(
                mapOf("k1" to SecretKeySpec(ByteArray(AesGcmSessionSealer.KEY_BYTES) { 3 }, "AES")),
                "k1",
            )
        val rules = BrowserSessionRules(IDLE, REFRESH_AHEAD)
        val refreshCalls = mutableListOf<String>()
        var refreshOutcome: RefreshOutcome = RefreshOutcome.Refused
        val refreshTokens =
            RefreshTokens { refreshToken, _ ->
                refreshCalls += refreshToken
                Mono.just(refreshOutcome)
            }
        val authenticator = BearerAuthenticator(fakeDecoder, SecurityConfiguration().jwtAuthenticationConverter())
        val filter = BrowserSessionFilter(sealer, rules, refreshTokens, authenticator, Clock.fixed(NOW, ZoneOffset.UTC))
        val json = JsonMapper.builder().build()

        val profile = route("identity-profile", metadata("authenticated", "standard"))
        val cartLines = route("cart-line-addition", metadata("anonymous", "standard"))
        val credentials = route("identity-credentials", metadata("anonymous", "auth"))
        val signOut = route("identity-sign-out", metadata("authenticated", "standard"))
        val registration = route("identity-registration", metadata("anonymous", "auth"))

        fun session(
            access: String = accessToken(),
            lastSeenAt: Instant = NOW,
        ) = SealedSession(access, "refresh-1", ACCOUNT, setOf("shopper"), lastSeenAt, NOW.minus(IDLE))

        fun request(
            method: String = "GET",
            path: String = "/api/v1/identity/accounts/me",
            cookie: String? = sealer.seal(session()),
            cookieName: String = "session",
            scheme: String = "http",
            fetchSite: String? = "same-origin",
            configure: MockServerHttpRequest.BaseBuilder<*>.() -> Unit = {},
        ): MockServerHttpRequest {
            val builder =
                MockServerHttpRequest.method(
                    HttpMethod.valueOf(method),
                    URI("$scheme://shop.example:8080$path"),
                )
            cookie?.let { builder.cookie(HttpCookie(cookieName, it)) }
            fetchSite?.let { builder.header("Sec-Fetch-Site", it) }
            builder.configure()
            return builder.build()
        }

        fun forwarded(exchange: MockServerWebExchange): ServerWebExchange =
            RecordingChain()
                .also {
                    filter
                        .filter(
                            exchange,
                            it,
                        ).block()
                }.forwarded
                .shouldNotBeNull()

        fun refusal(exchange: MockServerWebExchange): GatewayProblemException =
            shouldThrow<GatewayProblemException> { filter.filter(exchange, RecordingChain()).block() }

        fun committed(
            exchange: MockServerWebExchange,
            status: HttpStatus = HttpStatus.OK,
        ): HttpHeaders {
            exchange.response.statusCode = status
            exchange.response.setComplete().block()
            return exchange.response.headers
        }

        fun sessionIn(headers: HttpHeaders): SealedSession {
            val value =
                headers[SET_COOKIE].orEmpty().single {
                    it.startsWith(
                        "session=",
                    ) && !it.startsWith("session=;")
                }
            return sealer.unseal(value.removePrefix("session=").substringBefore(';')).shouldNotBeNull()
        }

        beforeTest {
            refreshCalls.clear()
            refreshOutcome = RefreshOutcome.Refused
        }

        test("runs before the route access filter") {
            filter.order shouldBe -400
        }

        test("unrouted requests, ignored routes and cookieless requests pass untouched") {
            exchange(request()).let { forwarded(it) shouldBeSameInstanceAs it }
            exchange(request(method = "POST", path = "/api/v1/identity/accounts"), registration).let {
                forwarded(it) shouldBeSameInstanceAs it
                committed(it)[SET_COOKIE].shouldBeNull()
            }
            exchange(request(cookie = null), profile).let { forwarded(it) shouldBeSameInstanceAs it }
            exchange(request(cookie = null) { header(HttpHeaders.AUTHORIZATION, "Bearer api-client") }, profile).let {
                forwarded(it) shouldBeSameInstanceAs it
            }
        }

        test("a cookie next to Authorization answers 400 validation with no-store and no cookie change") {
            val problem = refusal(exchange(request { header(HttpHeaders.AUTHORIZATION, "Bearer x") }, profile))
            problem.problem shouldBe GatewayProblem.BAD_REQUEST
            problem.headers shouldBe mapOf(HttpHeaders.CACHE_CONTROL to "no-store")
            problem.cookies.shouldBeEmpty()
        }

        test("a cross-site non-GET answers 403 forbidden with the cookie untouched") {
            val cross =
                exchange(
                    request(method = "POST", path = "/api/v1/cart/lines", fetchSite = "cross-site"),
                    cartLines,
                )
            refusal(cross).problem shouldBe GatewayProblem.FORBIDDEN
            val absent = request(method = "DELETE", path = "/api/v1/identity/sessions/current", fetchSite = null)
            refusal(exchange(absent, signOut)).problem shouldBe GatewayProblem.FORBIDDEN
        }

        test("an unsealable or idle cookie answers 401 with the cookie deleted; both names delete both") {
            refusal(exchange(request(cookie = "k1.garbage"), profile)).let { problem ->
                problem.problem shouldBe GatewayProblem.UNAUTHORIZED
                problem.cookies shouldBe listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")
                problem.headers[HttpHeaders.CACHE_CONTROL] shouldBe "no-store"
            }
            val idle = sealer.seal(session(lastSeenAt = NOW.minus(Duration.ofMinutes(IDLE_EXCEEDED_MINUTES))))
            refusal(
                exchange(request(cookie = idle, cookieName = "__Host-session", scheme = "https"), profile),
            ).cookies shouldBe
                listOf("__Host-session=; Max-Age=0; HttpOnly; Secure; SameSite=Strict; Path=/")
            val both = request { cookie(HttpCookie("__Host-session", sealer.seal(session()))) }
            refusal(exchange(both, profile)).cookies shouldBe BrowserCookies.deleteBoth(CookieKind.SESSION)
        }

        test("sign-out with an unusable cookie answers 204 and deletes it without forwarding") {
            val exchange =
                exchange(
                    request(method = "DELETE", path = "/api/v1/identity/sessions/current", cookie = "nope"),
                    signOut,
                )
            val chain = RecordingChain()
            filter.filter(exchange, chain).block()
            chain.forwarded.shouldBeNull()
            exchange.response.statusCode shouldBe HttpStatus.NO_CONTENT
            exchange.response.headers[SET_COOKIE] shouldBe
                listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")
            exchange.response.headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        }

        test("a valid cookie injects the bearer, authenticates it and renews the cookie on the way back") {
            val access = accessToken()
            val exchange = exchange(request(cookie = sealer.seal(session(access))), profile)
            val forwarded = forwarded(exchange)
            forwarded.request.headers.getFirst(HttpHeaders.AUTHORIZATION) shouldBe "Bearer $access"
            val authentication =
                exchange
                    .getAttribute<JwtAuthenticationToken>(
                        BROWSER_AUTHENTICATION_ATTRIBUTE,
                    ).shouldNotBeNull()
            authentication.token.subject shouldBe ACCOUNT
            authentication.authorities.map { it.authority } shouldContain "ROLE_shopper"
            refreshCalls.shouldBeEmpty()

            val headers = committed(exchange)
            val renewed = sessionIn(headers)
            renewed shouldBe session(access).seenAt(NOW)
            headers[SET_COOKIE].orEmpty().filter { it.startsWith("__Host-session=;") } shouldBe
                listOf("__Host-session=; Max-Age=0; HttpOnly; Secure; SameSite=Strict; Path=/")
            headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        }

        test("over HTTPS the renewed cookie is __Host-session with Secure and the plain name is deleted") {
            val exchange = exchange(request(scheme = "https"), profile)
            forwarded(exchange)
            val headers = committed(exchange)
            headers[SET_COOKIE].orEmpty().map { it.substringBefore('=') } shouldContainExactly
                listOf("__Host-session", "session")
            headers[SET_COOKIE].orEmpty().first() shouldBe
                "__Host-session=${headers[SET_COOKIE].orEmpty().first().removePrefix(
                    "__Host-session=",
                ).substringBefore(';')}; HttpOnly; Secure; SameSite=Strict; Path=/"
            headers[SET_COOKIE].orEmpty().last() shouldBe "session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/"
        }

        test("an upstream 401 and a sign-out delete the cookie instead of renewing it") {
            val unauthorized = exchange(request(), profile)
            forwarded(unauthorized)
            committed(unauthorized, HttpStatus.UNAUTHORIZED)[SET_COOKIE] shouldBe
                listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")

            val signingOut = exchange(request(method = "DELETE", path = "/api/v1/identity/sessions/current"), signOut)
            forwarded(signingOut)
                .request.headers
                .getFirst(HttpHeaders.AUTHORIZATION)
                .shouldNotBeNull()
            committed(signingOut, HttpStatus.NO_CONTENT)[SET_COOKIE] shouldBe
                listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")
        }

        test("an access token expiring within 60 s is refreshed through identity first, rotating the sealed pair") {
            val near = accessToken(NOW.plusSeconds(NEAR_EXPIRY_SECONDS))
            val rotatedAccess = accessToken(roles = listOf("shopper", "operator"))
            refreshOutcome = RefreshOutcome.Rotated(TokenPair(rotatedAccess, "refresh-2"))
            val exchange = exchange(request(cookie = sealer.seal(session(near))), profile)
            val forwarded = forwarded(exchange)
            refreshCalls shouldBe listOf("refresh-1")
            forwarded.request.headers.getFirst(HttpHeaders.AUTHORIZATION) shouldBe "Bearer $rotatedAccess"
            val renewed = sessionIn(committed(exchange))
            renewed.accessToken shouldBe rotatedAccess
            renewed.refreshToken shouldBe "refresh-2"
            renewed.roles shouldBe setOf("shopper", "operator")
            renewed.lastSeenAt shouldBe NOW
        }

        test("a refused refresh ends the session with 401 and deletion; an unavailable identity answers 503") {
            val near = sealer.seal(session(accessToken(NOW.plusSeconds(NEAR_EXPIRY_SECONDS))))
            refusal(exchange(request(cookie = near), profile)).let { problem ->
                problem.problem shouldBe GatewayProblem.UNAUTHORIZED
                problem.cookies shouldBe listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")
            }
            refreshOutcome = RefreshOutcome.Unavailable(IllegalStateException("down"))
            refusal(exchange(request(cookie = near), profile)).let { problem ->
                problem.problem shouldBe GatewayProblem.UNAVAILABLE
                problem.cookies.shouldBeEmpty()
            }
        }

        test("a sealed token the resource server rejects answers 401 with deletion; unreadable keys answer 503") {
            refusal(exchange(request(cookie = sealer.seal(session(BAD_TOKEN))), profile)).let { problem ->
                problem.problem shouldBe GatewayProblem.UNAUTHORIZED
                problem.cookies shouldBe listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")
            }
            refusal(exchange(request(cookie = sealer.seal(session(UNAVAILABLE_TOKEN))), profile)).problem shouldBe
                GatewayProblem.UNAVAILABLE
        }

        test("sign-in in browser mode turns identity's 200 token pair into a summary and a cookie") {
            val signIn =
                request(
                    method = "POST",
                    path = "/api/v1/identity/sessions",
                    cookie = null,
                ) { header(BROWSER_SESSION_HEADER, "cookie") }
            val exchange = exchange(signIn, credentials)
            val forwarded = forwarded(exchange)
            forwarded.response.statusCode = HttpStatus.OK
            val access = accessToken()
            val body = tokenPairBody(access, "refresh-9").toByteArray()
            forwarded.response.writeWith(Mono.just(forwarded.response.bufferFactory().wrap(body))).block()

            val headers = exchange.response.headers
            json.readValue(exchange.response.bodyAsString.block(), Map::class.java) shouldBe
                mapOf("expiresAt" to NOW.plus(IDLE).toString(), "roles" to listOf("shopper"))
            headers.contentType shouldBe MediaType.APPLICATION_JSON
            headers.contentLength shouldBe
                exchange.response.bodyAsString
                    .block()!!
                    .toByteArray()
                    .size
                    .toLong()
            headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            sessionIn(headers) shouldBe SealedSession(access, "refresh-9", ACCOUNT, setOf("shopper"), NOW, NOW)
            headers[SET_COOKIE].orEmpty().last() shouldBe
                "__Host-session=; Max-Age=0; HttpOnly; Secure; SameSite=Strict; Path=/"
        }

        test("sign-in without the browser header, or a non-200 answer, passes identity's body through unchanged") {
            val plain = exchange(request(method = "POST", path = "/api/v1/identity/sessions"), credentials)
            forwarded(plain) shouldBeSameInstanceAs plain

            val refused =
                exchange(
                    request(method = "POST", path = "/api/v1/identity/sessions", cookie = null) {
                        header(BROWSER_SESSION_HEADER, "cookie")
                    },
                    credentials,
                )
            val forwarded = forwarded(refused)
            forwarded.response.statusCode = HttpStatus.UNAUTHORIZED
            forwarded.response
                .writeWith(
                    Mono.just(forwarded.response.bufferFactory().wrap("""{"type":"x"}""".toByteArray())),
                ).block()
            refused.response.bodyAsString.block() shouldBe """{"type":"x"}"""
            refused.response.headers[SET_COOKIE].shouldBeNull()
        }

        test("an unreadable token pair or a session over the cookie budget fails closed with 502") {
            fun answer(body: String): GatewayProblemException {
                val exchange =
                    exchange(
                        request(method = "POST", path = "/api/v1/identity/sessions", cookie = null) {
                            header(BROWSER_SESSION_HEADER, "cookie")
                        },
                        credentials,
                    )
                val forwarded = forwarded(exchange)
                forwarded.response.statusCode = HttpStatus.OK
                return shouldThrow<GatewayProblemException> {
                    forwarded.response
                        .writeWith(
                            Mono.just(forwarded.response.bufferFactory().wrap(body.toByteArray())),
                        ).block()
                }
            }
            answer("""{"accessToken":"opaque","refreshToken":"r"}""").problem shouldBe GatewayProblem.BAD_GATEWAY
            answer("not json").problem shouldBe GatewayProblem.BAD_GATEWAY
            answer(tokenPairBody(accessToken(), "r".repeat(BrowserCookies.MAX_COOKIE_BYTES))).problem shouldBe
                GatewayProblem.BAD_GATEWAY
        }

        test("a browser refresh sends the cookie's refresh token to identity and renews the cookie on 200") {
            val refresh =
                request(
                    method = "POST",
                    path = "/api/v1/identity/sessions/refresh",
                ) { header(BROWSER_SESSION_HEADER, "cookie") }
            val exchange = exchange(refresh, credentials)
            val forwarded = forwarded(exchange)
            val sent = DataBufferUtils.join(forwarded.request.body).block().shouldNotBeNull()
            String(ByteArray(sent.readableByteCount()).also(sent::read)) shouldBe """{"refreshToken":"refresh-1"}"""
            forwarded.request.headers.contentType shouldBe MediaType.APPLICATION_JSON
            forwarded.request.headers
                .getFirst(HttpHeaders.AUTHORIZATION)
                .shouldBeNull()

            forwarded.response.statusCode = HttpStatus.OK
            val rotated = accessToken()
            forwarded.response
                .writeWith(
                    Mono.just(
                        forwarded.response.bufferFactory().wrap(tokenPairBody(rotated, "refresh-2").toByteArray()),
                    ),
                ).block()
            val renewed = sessionIn(exchange.response.headers)
            renewed.accessToken shouldBe rotated
            renewed.refreshToken shouldBe "refresh-2"
            renewed.issuedAt shouldBe NOW.minus(IDLE)
            json.readValue(exchange.response.bodyAsString.block(), Map::class.java)["expiresAt"] shouldBe
                NOW.plus(IDLE).toString()
        }

        test("a browser refresh identity refuses passes the 401 through and deletes the cookie; no cookie is 401 too") {
            val refresh =
                request(
                    method = "POST",
                    path = "/api/v1/identity/sessions/refresh",
                ) { header(BROWSER_SESSION_HEADER, "cookie") }
            val exchange = exchange(refresh, credentials)
            forwarded(exchange)
            committed(exchange, HttpStatus.UNAUTHORIZED)[SET_COOKIE] shouldBe
                listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")

            val missing =
                request(method = "POST", path = "/api/v1/identity/sessions/refresh", cookie = null) {
                    header(BROWSER_SESSION_HEADER, "cookie")
                }
            refusal(exchange(missing, credentials)).let { problem ->
                problem.problem shouldBe GatewayProblem.UNAUTHORIZED
                problem.cookies shouldBe listOf("session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/")
            }
            val apiRefresh = exchange(request(method = "POST", path = "/api/v1/identity/sessions/refresh"), credentials)
            forwarded(apiRefresh)
                .request.headers
                .getFirst(HttpHeaders.AUTHORIZATION)
                .shouldNotBeNull()
            apiRefresh.response
                .statusCode
                .shouldBeNull()
        }
    })

package com.ecommerce.gateway

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.gateway.browser.AesGcmSessionSealer
import com.ecommerce.gateway.browser.SealedSession
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.apache.hc.core5.http.HttpRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

private const val KID = "2026-10-a1"
private const val JWKS_PATH = "/.well-known/jwks.json"
private const val SIGN_IN = "/api/v1/identity/sessions"
private const val REFRESH = "/api/v1/identity/sessions/refresh"
private const val SIGN_OUT = "/api/v1/identity/sessions/current"
private const val PROFILE = "/api/v1/identity/accounts/me"
private const val CART = "/api/v1/cart"
private const val CART_LINES = "/api/v1/cart/lines"
private const val CART_MERGE = "/api/v1/cart/merge"
private const val CART_TOKEN = "tok-cart-1"
private const val NEW_CART_TOKEN = "tok-cart-2"
private const val TOKEN_LIFETIME_MINUTES = 15L
private const val NEAR_EXPIRY_SECONDS = 30L
private const val IDLE_EXCEEDED_MINUTES = 31L
private const val RETRY_AFTER_SECONDS = 42

// The bucket refills continuously, one token every minute / limit. With the production budget of 600 a token comes
// back every 100 ms, before the replayed request arrives, so this verification runs with a budget of 60 (one token a
// second): the state empties it with sequential requests and stops at the first 429.
private const val BROWSE_PER_MINUTE = 60
private const val EXHAUST_ATTEMPTS = BROWSE_PER_MINUTE * 20
private const val SHELL = "<!doctype html><html><body><div id=\"root\"></div></body></html>"
private val KEY_BYTES = ByteArray(AesGcmSessionSealer.KEY_BYTES) { (it * 3 + 1).toByte() }
private val SESSION_COOKIES = setOf("session", "__Host-session")
private val CART_COOKIES = setOf("cart", "__Host-cart")

/**
 * Provider states G1 to G19 of feature 005 `contracts/pact-matrix.md` for the `storefront-gateway` pact: the real
 * gateway in front of WireMock identity (with the JWKS of the RFC 8037 test key), cart, storefront and collector
 * upstreams, sealing cookies with a fixed `BROWSER_SESSION_KEY`.
 *
 * The sealed cookie values only this provider can mint are made available in two ways: as provider-state values
 * (`sessionCookie`, `cartCookie`) for consumer requests that use `fromProviderState` generators, and by rewriting
 * the `Cookie` header of the replayed request, where any `session`/`__Host-session` or `cart`/`__Host-cart` value
 * is replaced by the state's sealed value. Shared by [StorefrontGatewayProviderIT] (pact folder).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
// Abstract: JUnit runs only the subclass, which chooses the pact source; one method per provider state of the matrix.
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
abstract class StorefrontProviderStates {
    @LocalServerPort
    protected var port: Int = 0

    private var sessionCookie: String? = null
    private var cartCookie: String? = null

    companion object {
        private val collectorPort: Int = ServerSocket(0).use { it.localPort }
        private val upstream = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val collector = WireMockServer(wireMockConfig().port(collectorPort)).apply { start() }
        private val sealer = AesGcmSessionSealer(mapOf("k1" to SecretKeySpec(KEY_BYTES, "AES")), "k1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            listOf("IDENTITY_URL", "CART_URL", "STOREFRONT_URL").forEach { registry.add(it) { upstream.baseUrl() } }
            registry.add("OTEL_COLLECTOR_URL") { "http://localhost:$collectorPort" }
            registry.add("JWKS_URI") { upstream.baseUrl() + JWKS_PATH }
            registry.add("gateway.rate-limit.requests-per-minute.browse") { BROWSE_PER_MINUTE }
            registry.add("BROWSER_SESSION_KEY") { Base64.getEncoder().encodeToString(KEY_BYTES) }
        }
    }

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", port)
        sessionCookie = null
        cartCookie = null
        upstream.resetAll()
        upstream.stubFor(get(JWKS_PATH).willReturn(okJson(RfcKey.jwks(KID))))
        if (!collector.isRunning) collector.start()
        collector.resetAll()
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun gatewayHonoursTheStorefront(
        context: PactVerificationContext?,
        request: HttpRequest?,
    ) {
        request?.let(::sealCookiesOf)
        context?.verifyInteraction()
    }

    /** Replaces placeholder cookie values of the replayed request by the sealed ones of the current state. */
    private fun sealCookiesOf(request: HttpRequest) {
        val header = request.getFirstHeader(HttpHeaders.COOKIE) ?: return
        val rewritten =
            header.value.split(';').map(String::trim).filter(String::isNotEmpty).joinToString("; ") { cookie ->
                val name = cookie.substringBefore('=')
                when {
                    name in SESSION_COOKIES && sessionCookie != null -> "$name=$sessionCookie"
                    name in CART_COOKIES && cartCookie != null -> "$name=$cartCookie"
                    else -> cookie
                }
            }
        request.removeHeaders(HttpHeaders.COOKIE)
        request.addHeader(HttpHeaders.COOKIE, rewritten)
    }

    private fun stateValues(): Map<String, Any> =
        buildMap {
            sessionCookie?.let { put("sessionCookie", it) }
            cartCookie?.let { put("cartCookie", it) }
        }

    private fun tokenPair(
        access: String,
        refresh: String,
    ) = """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresIn":900}"""

    private fun problem(
        status: HttpStatus,
        slug: String,
        title: String,
    ) = """{"type":"https://ecommerce.example/problems/$slug","title":"$title","status":${status.value()}}"""

    private fun session(
        access: String = RfcKey.token(KID),
        lastSeenAt: Instant = Instant.now(),
    ): String =
        sealer.seal(SealedSession(access, "refresh-ana-1", RfcKey.SUBJECT, setOf("shopper"), lastSeenAt, lastSeenAt))

    /** The upstreams a cookie-authenticated request may reach. */
    private fun stubAuthenticatedUpstreams() {
        upstream.stubFor(
            get(
                PROFILE,
            ).willReturn(okJson("""{"id":"${RfcKey.SUBJECT}","email":"ana@example.com","roles":["shopper"]}""")),
        )
        upstream.stubFor(delete(SIGN_OUT).willReturn(aResponse().withStatus(HttpStatus.NO_CONTENT.value())))
        upstream.stubFor(get(CART).willReturn(okJson("""{"lines":[],"revision":1}""")))
        upstream.stubFor(
            post(CART_LINES).willReturn(
                aResponse()
                    .withStatus(HttpStatus.CREATED.value())
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"lines":[],"revision":2}"""),
            ),
        )
        upstream.stubFor(post(REFRESH).willReturn(okJson(tokenPair(RfcKey.token(KID), "refresh-ana-2"))))
    }

    @State("identity accepts the credentials of ana@example.com and issues a token pair")
    fun identityAcceptsCredentials(): Map<String, Any> {
        upstream.stubFor(post(SIGN_IN).willReturn(okJson(tokenPair(RfcKey.token(KID), "refresh-ana-1"))))
        return stateValues()
    }

    @State("identity rejects the credentials of ana@example.com")
    fun identityRejectsCredentials() {
        upstream.stubFor(
            post(SIGN_IN).willReturn(problemResponse(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized")),
        )
    }

    @State("identity reports ana@example.com as unverified")
    fun identityReportsUnverified() {
        upstream.stubFor(post(SIGN_IN).willReturn(problemResponse(HttpStatus.FORBIDDEN, "forbidden", "Forbidden")))
    }

    @State("identity throttles ana@example.com")
    fun identityThrottles() {
        upstream.stubFor(
            post(SIGN_IN).willReturn(
                problemResponse(HttpStatus.TOO_MANY_REQUESTS, "throttled", "Too many requests")
                    .withHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString()),
            ),
        )
    }

    @State("a session cookie for ana@example.com exists and identity rotates refresh tokens")
    fun sessionCookieAndRotation(): Map<String, Any> = sessionCookieExists()

    @State("identity rejects the refresh token")
    fun identityRejectsRefresh(): Map<String, Any> {
        sessionCookieExists()
        upstream.stubFor(
            post(REFRESH).willReturn(problemResponse(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized")),
        )
        return stateValues()
    }

    @State("a session cookie for ana@example.com exists")
    fun sessionCookieExists(): Map<String, Any> {
        sessionCookie = session()
        stubAuthenticatedUpstreams()
        return stateValues()
    }

    @State("a session cookie for ana@example.com exists whose access token expires in 30 seconds")
    fun sessionCookieNearExpiry(): Map<String, Any> {
        val issuedAt = Instant.now().minus(Duration.ofMinutes(TOKEN_LIFETIME_MINUTES)).plusSeconds(NEAR_EXPIRY_SECONDS)
        sessionCookie = session(access = RfcKey.token(KID, issuedAt))
        stubAuthenticatedUpstreams()
        return stateValues()
    }

    @State("a session cookie for ana@example.com exists whose last activity was 31 minutes ago")
    fun sessionCookieIdle(): Map<String, Any> {
        sessionCookie = session(lastSeenAt = Instant.now().minus(Duration.ofMinutes(IDLE_EXCEEDED_MINUTES)))
        stubAuthenticatedUpstreams()
        return stateValues()
    }

    @State("a session cookie that cannot be unsealed exists")
    fun sessionCookieUnsealable(): Map<String, Any> {
        sessionCookie = "k1.bm90LWEtc2VhbGVkLXZhbHVl"
        stubAuthenticatedUpstreams()
        return stateValues()
    }

    @State("no anonymous cart exists")
    fun noAnonymousCart() {
        upstream.stubFor(
            post(CART_LINES).willReturn(
                aResponse()
                    .withStatus(HttpStatus.CREATED.value())
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .withHeader("X-Cart-Token", NEW_CART_TOKEN)
                    .withBody("""{"lines":[],"revision":1}"""),
            ),
        )
    }

    @State("a cart cookie for token tok-cart-1 exists")
    fun cartCookieExists(): Map<String, Any> {
        cartCookie = sealer.sealToken(CART_TOKEN)
        upstream.stubFor(get(CART).willReturn(okJson("""{"lines":[],"revision":1}""")))
        return stateValues()
    }

    @State("a session cookie for ana@example.com and a cart cookie for token tok-cart-1 exist")
    fun bothCookiesExist(): Map<String, Any> {
        sessionCookieExists()
        cartCookie = sealer.sealToken(CART_TOKEN)
        upstream.stubFor(post(CART_MERGE).willReturn(okJson("""{"mergedLines":1,"cappedLines":[]}""")))
        return stateValues()
    }

    @State("a merge for tok-cart-1 is in progress")
    fun mergeInProgress(): Map<String, Any> {
        bothCookiesExist()
        upstream.stubFor(post(CART_MERGE).willReturn(problemResponse(HttpStatus.CONFLICT, "conflict", "Conflict")))
        return stateValues()
    }

    @State("no cookies exist")
    fun noCookies() {
        noAnonymousCart()
    }

    @State("the telemetry collector accepts OTLP")
    fun collectorAcceptsOtlp() {
        collector.stubFor(post("/v1/traces").willReturn(okJson("{}")))
        collector.stubFor(post("/v1/logs").willReturn(okJson("""{"partialSuccess":{"rejectedLogRecords":0}}""")))
    }

    @State("the browse budget of the client is exhausted")
    fun browseBudgetExhausted() {
        collectorAcceptsOtlp()
        val client = WebClient.create("http://localhost:$port")
        repeat(EXHAUST_ATTEMPTS) { attempt ->
            val status =
                client
                    .post()
                    .uri("/api/v1/telemetry/v1/logs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"resourceLogs":[]}""")
                    .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode()) }
                    .block()
            if (status == HttpStatus.TOO_MANY_REQUESTS) return
            check(attempt < EXHAUST_ATTEMPTS - 1) { "the browse budget could not be exhausted" }
        }
    }

    @State("the telemetry collector is not running")
    fun collectorNotRunning() {
        collector.stop()
    }

    @State("the storefront upstream serves index.html")
    fun storefrontServesShell() {
        upstream.stubFor(
            any(urlPathMatching("/|/products/.*")).willReturn(
                aResponse()
                    .withStatus(HttpStatus.OK.value())
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                    .withHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                    .withBody(SHELL),
            ),
        )
        upstream.stubFor(
            any(
                urlPathEqualTo("/api/v1/unknown"),
            ).willReturn(aResponse().withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())),
        )
    }

    private fun problemResponse(
        status: HttpStatus,
        slug: String,
        title: String,
    ) = aResponse()
        .withStatus(status.value())
        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        .withBody(problem(status, slug, title))
}

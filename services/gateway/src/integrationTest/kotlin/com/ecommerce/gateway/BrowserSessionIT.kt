package com.ecommerce.gateway

import com.ecommerce.gateway.browser.AesGcmSessionSealer
import com.ecommerce.gateway.browser.SealedSession
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

private const val JWKS_PATH = "/.well-known/jwks.json"
private const val SIGN_IN = "/api/v1/identity/sessions"
private const val REFRESH = "/api/v1/identity/sessions/refresh"
private const val SIGN_OUT = "/api/v1/identity/sessions/current"
private const val PROFILE = "/api/v1/identity/accounts/me"
private const val BROWSER_SESSION = "X-Browser-Session"
private const val SESSION_COOKIE = "session"
private const val HOST_SESSION_COOKIE = "__Host-session"
private const val SESSION_ATTRIBUTES = "HttpOnly; SameSite=Strict; Path=/"
private const val IDLE_EXCEEDED_MINUTES = 31L
private const val NEAR_EXPIRY_SECONDS = 30L
private const val TOKEN_LIFETIME_MINUTES = 15L
private const val ORDER_ID_LIKE = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"
private val IDLE: Duration = Duration.ofMinutes(30)
private val KEY_BYTES = ByteArray(AesGcmSessionSealer.KEY_BYTES) { (it + 1).toByte() }

/**
 * T017 (gateway-browser-session.yaml): the browser session end to end against a WireMock identity and JWKS, with the
 * fixed `BROWSER_SESSION_KEY` of the run, so the test seals and unseals the cookies the gateway exchanges.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Suppress("TooManyFunctions") // one function per rule of the contract
class BrowserSessionIT(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy { gatewayClient(port) }

    companion object {
        private val upstream = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        private val signingKey = TestSigningKey("browser-key-1")
        private val sealer = AesGcmSessionSealer(mapOf("k1" to SecretKeySpec(KEY_BYTES, "AES")), "k1")

        @JvmStatic
        @DynamicPropertySource
        fun upstreams(registry: DynamicPropertyRegistry) {
            listOf("IDENTITY_URL", "CART_URL").forEach { registry.add(it) { upstream.baseUrl() } }
            registry.add("JWKS_URI") { upstream.baseUrl() + JWKS_PATH }
            registry.add("BROWSER_SESSION_KEY") { Base64.getEncoder().encodeToString(KEY_BYTES) }
        }

        @JvmStatic
        @AfterAll
        fun stopUpstream() = upstream.stop()
    }

    private val accessToken = signingKey.token()

    @BeforeEach
    fun stubIdentity() {
        upstream.resetAll()
        upstream.stubFor(get(JWKS_PATH).willReturn(okJson(TestSigningKey.jwks(signingKey))))
        upstream.stubFor(post(SIGN_IN).willReturn(okJson(tokenPair(accessToken, "refresh-1"))))
        upstream.stubFor(post(REFRESH).willReturn(okJson(tokenPair(signingKey.token(), "refresh-2"))))
        upstream.stubFor(delete(SIGN_OUT).willReturn(aResponse().withStatus(HttpStatus.NO_CONTENT.value())))
        upstream.stubFor(get(PROFILE).willReturn(okJson("""{"email":"ana@example.com"}""")))
        upstream.stubFor(
            post("/api/v1/identity/accounts").willReturn(aResponse().withStatus(HttpStatus.ACCEPTED.value())),
        )
        upstream.stubFor(
            post("/api/v1/identity/password-resets").willReturn(aResponse().withStatus(HttpStatus.ACCEPTED.value())),
        )
    }

    private fun tokenPair(
        access: String,
        refresh: String,
    ) = """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresIn":900}"""

    private fun session(
        access: String = accessToken,
        lastSeenAt: Instant = Instant.now(),
    ) = SealedSession(access, "refresh-1", SHOPPER_ID, setOf("shopper"), lastSeenAt, lastSeenAt)

    private fun cookieOf(session: SealedSession) = sealer.seal(session)

    private fun signIn(browserMode: Boolean) =
        client
            .post()
            .uri(SIGN_IN)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (browserMode) header(BROWSER_SESSION, "cookie") }
            .bodyValue("""{"email":"ana@example.com","password":"S3cure-passphrase!"}""")
            .exchange()

    /** The `Set-Cookie` values of a response, by cookie name. */
    private fun HttpHeaders.setCookies(): Map<String, String> =
        this[HttpHeaders.SET_COOKIE].orEmpty().associateBy { it.substringBefore('=') }

    private fun sessionValue(headers: HttpHeaders): String =
        headers
            .setCookies()
            .getValue(SESSION_COOKIE)
            .removePrefix("$SESSION_COOKIE=")
            .substringBefore(';')

    @Test
    fun `sign-in in browser mode answers the tokenless summary and sets the session cookie`() {
        val result =
            signIn(browserMode = true)
                .expectStatus()
                .isOk
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(Map::class.java)
                .returnResult()
        val body = result.responseBody.shouldNotBeNull()
        body.keys shouldBe setOf("expiresAt", "roles")
        body["roles"] shouldBe listOf("shopper")
        val expiresAt = Instant.parse(body["expiresAt"].toString())

        val cookies = result.responseHeaders.setCookies()
        cookies.keys shouldBe setOf(SESSION_COOKIE, HOST_SESSION_COOKIE)
        cookies.getValue(SESSION_COOKIE) shouldBe
            "$SESSION_COOKIE=${sessionValue(result.responseHeaders)}; $SESSION_ATTRIBUTES"
        cookies.getValue(SESSION_COOKIE) shouldNotContain "Max-Age"
        cookies.getValue(HOST_SESSION_COOKIE) shouldBe
            "$HOST_SESSION_COOKIE=; Max-Age=0; HttpOnly; Secure; SameSite=Strict; Path=/"
        val sealed = sealer.unseal(sessionValue(result.responseHeaders)).shouldNotBeNull()
        sealed.accessToken shouldBe accessToken
        sealed.refreshToken shouldBe "refresh-1"
        sealed.accountId shouldBe SHOPPER_ID
        sealed.roles shouldBe setOf("shopper")
        sealed.expiresAt(IDLE) shouldBe expiresAt
        result.responseBody.toString() shouldNotContain accessToken
    }

    @Test
    fun `sign-in without the browser header keeps identity's token body and sets no cookie`() {
        signIn(browserMode = false)
            .expectStatus()
            .isOk
            .expectHeader()
            .doesNotExist(HttpHeaders.SET_COOKIE)
            .expectBody()
            .json(tokenPair(accessToken, "refresh-1"))
    }

    @Test
    fun `a cookie request injects the bearer upstream and re-sets the cookie with a new lastSeenAt`() {
        val before = Instant.now().minusSeconds(NEAR_EXPIRY_SECONDS)
        val headers =
            client
                .get()
                .uri(PROFILE)
                .cookie(SESSION_COOKIE, cookieOf(session(lastSeenAt = before)))
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .json("""{"email":"ana@example.com"}""")
                .returnResult()
                .responseHeaders

        val forwarded = upstream.findAll(getRequestedFor(urlPathEqualTo(PROFILE))).single()
        forwarded.getHeader(HttpHeaders.AUTHORIZATION) shouldBe "Bearer $accessToken"
        forwarded.getHeader("X-Account-Id") shouldBe SHOPPER_ID
        val renewed = sealer.unseal(sessionValue(headers)).shouldNotBeNull()
        renewed.lastSeenAt.isAfter(before) shouldBe true
        renewed.accessToken shouldBe accessToken
        headers.setCookies().getValue(HOST_SESSION_COOKIE) shouldStartWith "$HOST_SESSION_COOKIE=; Max-Age=0"
        upstream.findAll(postRequestedFor(urlPathEqualTo(REFRESH))) shouldHaveSize 0
    }

    @Test
    fun `an access token about to expire is refreshed through identity before forwarding, rotating the pair`() {
        val nearExpiry =
            signingKey.token(
                issuedAt =
                    Instant
                        .now()
                        .minus(
                            Duration.ofMinutes(TOKEN_LIFETIME_MINUTES),
                        ).plusSeconds(NEAR_EXPIRY_SECONDS),
            )
        val headers =
            client
                .get()
                .uri(PROFILE)
                .cookie(SESSION_COOKIE, cookieOf(session(access = nearExpiry)))
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseHeaders

        upstream.verify(
            postRequestedFor(urlPathEqualTo(REFRESH))
                .withRequestBody(equalToJson("""{"refreshToken":"refresh-1"}"""))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE)),
        )
        val rotated = sealer.unseal(sessionValue(headers)).shouldNotBeNull()
        rotated.refreshToken shouldBe "refresh-2"
        rotated.accessToken shouldStartWith "eyJ"
        upstream
            .findAll(
                getRequestedFor(urlPathEqualTo(PROFILE)),
            ).single()
            .getHeader(HttpHeaders.AUTHORIZATION) shouldBe
            "Bearer ${rotated.accessToken}"
    }

    @Test
    fun `an idle or tampered cookie answers 401 problem, no-store, correlation id and a deletion cookie`() {
        val idle = cookieOf(session(lastSeenAt = Instant.now().minus(Duration.ofMinutes(IDLE_EXCEEDED_MINUTES))))
        val tampered = cookieOf(session()).dropLast(1) + "x"
        listOf(idle, tampered, "garbage").forEach { cookie ->
            val problem =
                client
                    .get()
                    .uri(PROFILE)
                    .cookie(SESSION_COOKIE, cookie)
                    .header(CORRELATION, "browser-session-0001")
                    .exchange()
                    .expectProblem(HttpStatus.UNAUTHORIZED, "unauthorized")
            problem.correlationHeader shouldBe "browser-session-0001"
            problem.headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            problem.headers[HttpHeaders.SET_COOKIE] shouldContainExactly
                listOf("$SESSION_COOKIE=; Max-Age=0; $SESSION_ATTRIBUTES")
            problem.body["detail"].toString() shouldContain "expired"
        }
        upstream.findAll(getRequestedFor(urlPathEqualTo(PROFILE))) shouldHaveSize 0
    }

    @Test
    fun `a cookie together with Authorization answers 400 validation and forwards nothing`() {
        val problem =
            client
                .get()
                .uri(PROFILE)
                .cookie(SESSION_COOKIE, cookieOf(session()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .exchange()
                .expectProblem(HttpStatus.BAD_REQUEST, "validation")
        problem.headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        problem.headers[HttpHeaders.SET_COOKIE].shouldBeNull()
        upstream.findAll(anyRequestedFor(urlPathEqualTo(PROFILE))) shouldHaveSize 0
    }

    @Test
    fun `a cross-site or origin-less non-GET with the cookie answers 403 forbidden`() {
        listOf("cross-site", "same-site", null).forEach { fetchSite ->
            client
                .post()
                .uri("/api/v1/cart/lines")
                .cookie(SESSION_COOKIE, cookieOf(session()))
                .apply { fetchSite?.let { header("Sec-Fetch-Site", it) } }
                .bodyValue("""{"productId":"$ORDER_ID_LIKE","quantity":1}""")
                .exchange()
                .expectProblem(HttpStatus.FORBIDDEN, "forbidden")
        }
        client
            .post()
            .uri("/api/v1/cart/lines")
            .cookie(SESSION_COOKIE, cookieOf(session()))
            .header("Sec-Fetch-Site", "same-origin")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .bodyValue("{}")
            .exchange()
            .expectProblem(HttpStatus.FORBIDDEN, "forbidden")
        upstream.findAll(postRequestedFor(urlPathEqualTo("/api/v1/cart/lines"))) shouldHaveSize 0
    }

    @Test
    fun `sign-out answers 204 and deletes the cookie, also when the cookie cannot be unsealed`() {
        client
            .delete()
            .uri(SIGN_OUT)
            .cookie(SESSION_COOKIE, cookieOf(session()))
            .header("Sec-Fetch-Site", "same-origin")
            .exchange()
            .expectStatus()
            .isNoContent
            .expectHeader()
            .valueEquals(HttpHeaders.SET_COOKIE, "$SESSION_COOKIE=; Max-Age=0; $SESSION_ATTRIBUTES")
        upstream.verify(
            deleteRequestedFor(
                urlPathEqualTo(SIGN_OUT),
            ).withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer $accessToken")),
        )

        client
            .delete()
            .uri(SIGN_OUT)
            .cookie(SESSION_COOKIE, "unsealable")
            .header("Sec-Fetch-Site", "same-origin")
            .exchange()
            .expectStatus()
            .isNoContent
            .expectHeader()
            .valueEquals(HttpHeaders.SET_COOKIE, "$SESSION_COOKIE=; Max-Age=0; $SESSION_ATTRIBUTES")
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        upstream.findAll(deleteRequestedFor(urlPathEqualTo(SIGN_OUT))) shouldHaveSize 1
    }

    @Test
    fun `over HTTPS (X-Forwarded-Proto) the cookie is __Host-session with Secure and session is deleted`() {
        val cookies =
            client
                .post()
                .uri(SIGN_IN)
                .header(BROWSER_SESSION, "cookie")
                .header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"email":"ana@example.com","password":"S3cure-passphrase!"}""")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)
                .responseHeaders
                .setCookies()
        cookies.getValue(HOST_SESSION_COOKIE) shouldStartWith "$HOST_SESSION_COOKIE=k1."
        cookies.getValue(HOST_SESSION_COOKIE).substringAfter(';') shouldBe " HttpOnly; Secure; SameSite=Strict; Path=/"
        cookies.getValue(SESSION_COOKIE) shouldBe "$SESSION_COOKIE=; Max-Age=0; $SESSION_ATTRIBUTES"

        // The __Host- cookie is accepted back and renewed under its own name; both names at once are refused.
        val sealed = cookies.getValue(HOST_SESSION_COOKIE).removePrefix("$HOST_SESSION_COOKIE=").substringBefore(';')
        client
            .get()
            .uri(PROFILE)
            .header("X-Forwarded-Proto", "https")
            .cookie(HOST_SESSION_COOKIE, sealed)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .value(HttpHeaders.SET_COOKIE) { it shouldStartWith "$HOST_SESSION_COOKIE=k1." }
        client
            .get()
            .uri(PROFILE)
            .cookie(HOST_SESSION_COOKIE, sealed)
            .cookie(SESSION_COOKIE, sealed)
            .exchange()
            .expectProblem(HttpStatus.UNAUTHORIZED, "unauthorized")
            .headers[HttpHeaders.SET_COOKIE]
            .shouldNotBeNull()
            .map { it.substringBefore('=') } shouldContainExactly listOf(HOST_SESSION_COOKIE, SESSION_COOKIE)
    }

    @Test
    fun `registration and password resets ignore an existing cookie`() {
        listOf("/api/v1/identity/accounts", "/api/v1/identity/password-resets").forEach { path ->
            client
                .post()
                .uri(path)
                .cookie(SESSION_COOKIE, cookieOf(session()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"email":"new@example.com"}""")
                .exchange()
                .expectStatus()
                .isAccepted
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
            upstream.verify(postRequestedFor(urlPathEqualTo(path)).withHeader(HttpHeaders.AUTHORIZATION, absent()))
        }
        upstream.findAll(anyRequestedFor(anyUrl())).filter { it.url == REFRESH } shouldHaveSize 0
    }

    @Test
    fun `a browser refresh sends the sealed refresh token to identity and answers the summary with a renewed cookie`() {
        val result =
            client
                .post()
                .uri(REFRESH)
                .header(BROWSER_SESSION, "cookie")
                .header("Sec-Fetch-Site", "same-origin")
                .cookie(SESSION_COOKIE, cookieOf(session()))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
        result.responseBody.shouldNotBeNull().keys shouldBe setOf("expiresAt", "roles")
        upstream.verify(
            postRequestedFor(urlPathEqualTo(REFRESH)).withRequestBody(equalToJson("""{"refreshToken":"refresh-1"}""")),
        )
        sealer.unseal(sessionValue(result.responseHeaders)).shouldNotBeNull().refreshToken shouldBe "refresh-2"

        client
            .post()
            .uri(REFRESH)
            .header(BROWSER_SESSION, "cookie")
            .header("Sec-Fetch-Site", "same-origin")
            .exchange()
            .expectProblem(HttpStatus.UNAUTHORIZED, "unauthorized")
            .headers[HttpHeaders.SET_COOKIE] shouldContainExactly
            listOf("$SESSION_COOKIE=; Max-Age=0; $SESSION_ATTRIBUTES")
    }
}

package com.ecommerce.platform.security

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testapp.PlatformWebTest
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID

class PlatformSecurityTest(
    @LocalServerPort port: Int,
) : PlatformWebTest(port) {
    private val accountId: UUID = UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d")

    private fun get(
        path: String,
        token: String? = null,
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .headers { headers -> token?.let { headers.setBearerAuth(it) } }
            .exchange()

    @Test
    fun `public paths are open to anonymous callers`() {
        get("/api/v1/public/correlation").expectStatus().isOk
    }

    @Test
    fun `protected paths need a token`() {
        get("/api/v1/me")
            .expectHeader()
            .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectProblem(ProblemType.UNAUTHORIZED)["detail"] shouldBe "Authentication is required."
    }

    @Test
    fun `a valid token authenticates the account with its roles`() {
        val body =
            get("/api/v1/me", jwt.tokenFor(accountId, listOf("shopper", "operator", "auditor")))
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody
                .orEmpty()
        body["accountId"] shouldBe accountId.toString()
        body["roles"] shouldBe listOf("shopper", "operator")
    }

    @Test
    fun `tokens with the wrong audience, issuer, key, subject or lifetime are rejected`() {
        val other = JwtFixture()
        listOf(
            jwt.tokenFor(accountId, audience = "another-api"),
            jwt.tokenFor(accountId, issuer = "https://evil.example"),
            jwt.tokenFor(accountId, expiresIn = Duration.ofMinutes(-5)),
            other.tokenFor(accountId),
            "not-a-jwt",
        ).forEach { token ->
            get("/api/v1/me", token)
                .expectHeader()
                .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectProblem(ProblemType.UNAUTHORIZED)["detail"] shouldBe "The access token is invalid or expired."
        }
    }

    @Test
    fun `an invalid token is rejected even on a public path`() {
        get("/api/v1/public/correlation", "not-a-jwt").expectProblem(ProblemType.UNAUTHORIZED)
    }

    @Test
    fun `role checks in handlers answer 403 problems`() {
        get("/api/v1/operator", jwt.tokenFor(accountId, listOf("shopper")))
            .expectProblem(ProblemType.FORBIDDEN)["detail"] shouldBe "This operation requires the operator role."
        get("/api/v1/operator", jwt.tokenFor(accountId, listOf("operator"))).expectStatus().isOk
    }

    @Test
    fun `internal endpoints need the internal token`() {
        get("/internal/ping").expectProblem(ProblemType.UNAUTHORIZED)["detail"] shouldBe
            "A valid internal token is required."
        client
            .get()
            .uri("/internal/ping")
            .header(InternalToken.HEADER, "wrong")
            .exchange()
            .expectProblem(ProblemType.UNAUTHORIZED)
        client
            .get()
            .uri("/internal/ping")
            .header(InternalToken.HEADER, InternalToken.TEST)
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `responses carry the security headers`() {
        get("/api/v1/me", jwt.tokenFor(accountId))
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains")
            .expectHeader()
            .valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader()
            .valueEquals("X-Frame-Options", "DENY")
            .expectHeader()
            .valueEquals(
                "Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            ).expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        val publicCache =
            get("/api/v1/public/correlation")
                .expectStatus()
                .isOk
                .expectHeader()
                .valueEquals("X-Frame-Options", "DENY")
                .returnResult(String::class.java)
                .responseHeaders
                .cacheControl
        publicCache shouldNotBe "no-store"
    }

    @Test
    fun `CORS allows only the configured origins`() {
        client
            .options()
            .uri("/api/v1/me")
            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
        client
            .options()
            .uri("/api/v1/me")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `management endpoints are reachable without a token`() {
        get("/actuator/health").expectStatus().isOk
    }

    @Test
    fun `public paths are parsed with an optional method`() {
        PlatformSecurityProperties.PublicPath.parse("get /a/{*rest}") shouldBe
            PlatformSecurityProperties.PublicPath(org.springframework.http.HttpMethod.GET, "/a/{*rest}")
        PlatformSecurityProperties.PublicPath.parse(" /a ") shouldBe PlatformSecurityProperties.PublicPath(null, "/a")
    }
}

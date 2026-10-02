package com.ecommerce.gateway

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.ServerSocket
import java.time.Duration

const val CORRELATION = "X-Correlation-Id"
const val PROBLEMS = "https://ecommerce.example/problems/"
private val CLIENT_TIMEOUT: Duration = Duration.ofSeconds(30)

/** A client of the gateway's public port, patient enough for the upstream-timeout cases. */
fun gatewayClient(port: Int): WebTestClient =
    WebTestClient
        .bindToServer()
        .baseUrl("http://localhost:$port")
        .responseTimeout(CLIENT_TIMEOUT)
        .build()

/** A local port with nothing listening (connection refused). */
fun unusedPort(): Int = ServerSocket(0).use { it.localPort }

/** A gateway-generated RFC 9457 answer, as the client sees it. */
data class Problem(
    val body: Map<*, *>,
    val correlationHeader: String?,
    val headers: HttpHeaders,
)

/** Asserts the RFC 9457 shape of a gateway error and returns it. */
fun WebTestClient.ResponseSpec.expectProblem(
    status: HttpStatus,
    slug: String,
): Problem {
    val result =
        expectStatus()
            .isEqualTo(status)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(Map::class.java)
            .returnResult()
    val body = result.responseBody.shouldNotBeNull()
    val correlation = result.responseHeaders.getFirst(CORRELATION).shouldNotBeNull()
    body["type"] shouldBe PROBLEMS + slug
    body["status"] shouldBe status.value()
    body["correlationId"] shouldBe correlation
    return Problem(body, correlation, result.responseHeaders)
}

package com.ecommerce.platform.problem

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testapp.PlatformWebTest
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebInputException

class ProblemExceptionHandlerTest(
    @LocalServerPort port: Int,
) : PlatformWebTest(port) {
    @Test
    fun `an unexpected exception becomes a 500 problem without internals`() {
        val result =
            client
                .get()
                .uri("/api/v1/public/boom")
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String::class.java)
                .returnResult()
        val body = result.responseBody.orEmpty()
        body shouldNotContain "secret"
        body shouldNotContain "db-host"
        body shouldNotContain "IllegalStateException"
        body shouldBe
            """{"type":"about:blank","title":"Internal Server Error","status":500,"instance":"/api/v1/public/boom",""" +
            """"correlationId":"${result.responseHeaders.getFirst("X-Correlation-Id")}"}"""
    }

    @Test
    fun `a problem exception is rendered with its headers`() {
        val response = client.get().uri("/api/v1/public/conflict").exchange()
        response.expectHeader().valueEquals("Retry-After", "5")
        val body = response.expectProblem(ProblemType.CONFLICT)
        body["detail"] shouldBe "Already done."
        body["instance"] shouldBe "/api/v1/public/conflict"
    }

    @Test
    fun `an unreadable body is a 400 validation problem`() {
        val body =
            client
                .post()
                .uri("/api/v1/public/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json")
                .exchange()
                .expectProblem(ProblemType.VALIDATION, 400)
        body["title"] shouldBe "Bad request"
    }

    @Test
    fun `an unknown route is a 404 not-found problem`() {
        client
            .get()
            .uri("/api/v1/public/nowhere")
            .exchange()
            .expectProblem(ProblemType.NOT_FOUND)
    }

    @Test
    fun `statuses outside the catalogue become about-blank problems`() {
        val teapot =
            client
                .get()
                .uri("/api/v1/public/teapot")
                .exchange()
                .expectStatus()
                .isEqualTo(418)
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody
                .orEmpty()
        teapot["type"] shouldBe "about:blank"
        teapot["title"] shouldBe "I'm a teapot"
        teapot["detail"] shouldBe "short and stout"
        val gateway =
            client
                .get()
                .uri("/api/v1/public/gateway")
                .exchange()
                .expectStatus()
                .isEqualTo(502)
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody
                .orEmpty()
        gateway["title"] shouldBe "Bad Gateway"
        gateway.containsKey("detail") shouldBe false
    }

    @Test
    fun `a method that is not allowed keeps its Allow header`() {
        client
            .delete()
            .uri("/api/v1/public/boom")
            .exchange()
            .expectStatus()
            .isEqualTo(405)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader()
            .exists("Allow")
    }

    @Test
    fun `functional handlers answer with problem responses`() {
        val body =
            client
                .get()
                .uri("/api/v1/public/problem")
                .exchange()
                .expectProblem(ProblemType.NOT_FOUND)
        body["detail"] shouldBe "Nothing here."
        body["instance"] shouldBe "/api/v1/public/problem"
    }

    @Test
    fun `input errors map without a reason too`() {
        ProblemExceptionHandler.problemFor(ServerWebInputException("")).status shouldBe 400
    }
}

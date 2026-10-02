package com.ecommerce.platform.testing

import arrow.core.Either
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.core.result.ValidationError
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/** Kotest matchers and WebTestClient helpers for RFC 9457 problem bodies (service conventions section 3). */
object ProblemAssertions {
    private val MEMBERS = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    /** Matches a problem body (JSON members) of the platform type [type] with its default title for [status]. */
    fun beProblem(
        type: ProblemType,
        status: Int = type.status,
    ): Matcher<Map<String, Any?>> =
        Matcher { body ->
            val expected = mapOf("type" to type.uri.toString(), "title" to type.titleFor(status), "status" to status)
            val actual = mapOf("type" to body["type"], "title" to body["title"], "status" to body["status"])
            MatcherResult(
                actual == expected && body["correlationId"] is String,
                { "expected a $status ${type.slug} problem with a correlationId but was $body" },
                { "expected anything but a $status ${type.slug} problem" },
            )
        }

    /**
     * Asserts that the response is `application/problem+json` with [status], the platform [type], its title and a
     * correlation id equal to the echoed `X-Correlation-Id`; returns the body for further assertions.
     */
    fun WebTestClient.ResponseSpec.expectProblem(
        type: ProblemType,
        status: Int = type.status,
    ): Map<String, Any?> {
        val result =
            expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(MEMBERS)
                .returnResult()
        val body = result.responseBody.orEmpty()
        body should beProblem(type, status)
        val echoed = result.responseHeaders.getFirst("X-Correlation-Id")
        echoed shouldNotBe null
        body["correlationId"] shouldBe echoed
        return body
    }

    /** Asserts a [Problem] value is of [type] (and [status]). */
    infix fun Problem.shouldBeProblemOf(type: ProblemType) {
        this.type shouldBe type.uri
        this.status shouldBe type.status
    }

    /** Asserts that a validation result failed on [field]; returns the error. */
    fun <T> Either<ValidationError, T>.shouldBeInvalidOn(field: String): ValidationError {
        val error = leftOrNull()
        error?.field shouldBe field
        return error ?: throw AssertionError("expected a validation error on $field but was $this")
    }

    /** Asserts that a result is valid; returns the value. */
    fun <E, T : Any> Either<E, T>.shouldBeValid(): T =
        getOrNull() ?: throw AssertionError("expected a valid value but was $this")
}

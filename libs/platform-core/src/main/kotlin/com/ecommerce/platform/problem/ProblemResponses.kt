package com.ecommerce.platform.problem

import arrow.core.Either
import com.ecommerce.platform.core.problem.Problem
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import reactor.core.publisher.Mono

/**
 * Problem responses for functional (coroutine router) handlers. Every response is `application/problem+json`,
 * carries the request's correlation id and, unless the problem sets one, the request path as `instance`:
 *
 * ```kotlin
 * suspend fun get(request: ServerRequest): ServerResponse =
 *     findOrder(id).toServerResponse(request) { ServerResponse.ok().bodyValueAndAwait(it) }
 * ```
 */
object ProblemResponses {
    /** The problem response for [problem] (plus [headers], such as `Retry-After`). */
    suspend fun of(
        request: ServerRequest,
        problem: Problem,
        headers: Map<String, String> = emptyMap(),
    ): ServerResponse = builder(problem, headers).bodyValueAndAwait(body(request, problem))

    /** The reactive form of [of], for `router { }` handlers that return `Mono<ServerResponse>`. */
    fun mono(
        request: ServerRequest,
        problem: Problem,
        headers: Map<String, String> = emptyMap(),
    ): Mono<ServerResponse> = builder(problem, headers).bodyValue(body(request, problem))

    private fun builder(
        problem: Problem,
        headers: Map<String, String>,
    ): ServerResponse.BodyBuilder =
        ServerResponse
            .status(problem.status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .headers { target -> headers.forEach { (name, value) -> target.set(name, value) } }

    private fun body(
        request: ServerRequest,
        problem: Problem,
    ): Map<String, Any?> = ProblemWriter.enrich(problem, request.exchange()).toJsonMembers()
}

/** Answers with this problem. */
suspend fun Problem.toServerResponse(request: ServerRequest): ServerResponse = ProblemResponses.of(request, this)

/** Answers with the problem on the left or with [onSuccess] applied to the value on the right. */
suspend fun <T> Either<Problem, T>.toServerResponse(
    request: ServerRequest,
    onSuccess: suspend (T) -> ServerResponse,
): ServerResponse =
    when (this) {
        is Either.Left -> ProblemResponses.of(request, value)
        is Either.Right -> onSuccess(value)
    }

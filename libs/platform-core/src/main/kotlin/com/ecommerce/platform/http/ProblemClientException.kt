package com.ecommerce.platform.http

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.observability.withReactorThreadLocals
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange

/**
 * An error answer of an internal call: its HTTP [status] and the decoded RFC 9457 [problem] when the body was one
 * (`null` for an empty or foreign body). Adapters map it to their port's error type, for example a `not-found`
 * problem to "no such product".
 */
class ProblemClientException(
    val status: Int,
    val problem: Problem?,
) : RuntimeException("HTTP $status" + problem?.let { ": ${it.type} ${it.detail ?: it.title}" }.orEmpty()) {
    /** True when the problem is of the platform type with [slug]. */
    fun isType(slug: String): Boolean = problem?.type == ProblemType.uriOf(slug)

    companion object {
        private val MEMBERS = object : ParameterizedTypeReference<Map<String, Any?>>() {}

        /** Reads the error [response] into an exception (the body is consumed). */
        suspend fun from(response: ClientResponse): ProblemClientException {
            val members = runCatching { response.bodyToMono(MEMBERS).awaitSingleOrNull() }.getOrNull()
            return ProblemClientException(response.statusCode().value(), members?.let(Problem::fromJsonMembers))
        }
    }
}

/**
 * Sends the request and returns the decoded 2xx body, or a [ProblemClientException] for a 4xx/5xx answer.
 * Connection failures (after the retries of [WebClientDefaults]) still throw.
 *
 * The exchange runs [withReactorThreadLocals], so the client observation is a child of the caller's observation (the
 * inbound request's) even after the calling coroutine changed threads, and the request carries its `traceparent`.
 */
suspend inline fun <reified T : Any> WebClient.RequestHeadersSpec<*>.awaitBodyOrProblem():
    Either<ProblemClientException, T> =
    withReactorThreadLocals {
        awaitExchange { response ->
            if (response.statusCode().isError) {
                ProblemClientException.from(response).left()
            } else {
                response.awaitBody<T>().right()
            }
        }
    }

/** Like [awaitBodyOrProblem] for answers without a body (204) or an optional body. */
suspend inline fun <reified T : Any> WebClient.RequestHeadersSpec<*>.awaitOptionalBodyOrProblem():
    Either<ProblemClientException, T?> =
    withReactorThreadLocals {
        awaitExchange { response ->
            if (response.statusCode().isError) {
                ProblemClientException.from(response).left()
            } else {
                response.awaitBodyOrNull<T>().right()
            }
        }
    }

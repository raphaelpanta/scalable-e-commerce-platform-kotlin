package com.ecommerce.platform.problem

import arrow.core.toNonEmptyListOrNull
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono

/**
 * Turns every exception that escapes a handler or filter into an RFC 9457 `application/problem+json` response with
 * the request's `correlationId`. Ordered before Spring Boot's `ErrorWebExceptionHandler` (-1), so services never
 * return Boot's default error body. 5xx problems never carry internal details; their cause is logged instead.
 */
class ProblemExceptionHandler(
    private val writer: ProblemWriter,
) : WebExceptionHandler,
    Ordered {
    override fun getOrder(): Int = ORDER

    // Return type inferred: WebExceptionHandler's Java signature is Mono<Void>. A committed response cannot carry a
    // problem any more, so the error propagates (Mono.defer turns the rethrow into an error signal).
    override fun handle(
        exchange: ServerWebExchange,
        ex: Throwable,
    ) = Mono.defer {
        if (exchange.response.isCommitted) throw ex
        val problem = problemFor(ex)
        if (problem.status >= HttpStatus.INTERNAL_SERVER_ERROR.value() && ex !is ProblemException) {
            log.error("Unhandled error on {} {}", exchange.request.method, exchange.request.path.value(), ex)
        }
        writer.write(exchange, problem, headersFor(ex))
    }

    companion object {
        /** Before Boot's `ErrorWebExceptionHandler` (`@Order(-1)`). */
        const val ORDER: Int = -2
        private val log: Logger = LoggerFactory.getLogger(ProblemExceptionHandler::class.java)

        /** The problem that answers [ex]. */
        fun problemFor(ex: Throwable): Problem =
            when (ex) {
                is ProblemException -> ex.problem
                is WebExchangeBindException -> bindingProblem(ex)
                is ServerWebInputException -> Problem.badRequest(ex.reason ?: "The request is not valid.")
                is AccessDeniedException -> Problem.forbidden("You are not allowed to perform this operation.")
                is AuthenticationServiceException -> Problem.unavailable("Authentication is temporarily unavailable.")
                is AuthenticationException -> Problem.unauthorized("Authentication is required.")
                is ResponseStatusException -> statusProblem(ex.statusCode.value(), ex.reason)
                is ErrorResponse -> statusProblem(ex.statusCode.value(), ex.body.detail)
                else -> Problem.internal()
            }

        private fun headersFor(ex: Throwable): Map<String, String> =
            when (ex) {
                is ProblemException -> ex.headers
                is ErrorResponse -> ex.headers.toSingleValueMap()
                else -> emptyMap()
            }

        private fun bindingProblem(ex: WebExchangeBindException): Problem =
            ex.fieldErrors
                .map { ValidationError(it.field, it.defaultMessage ?: "is invalid") }
                .toNonEmptyListOrNull()
                ?.let { Problem.validation(it) }
                ?: Problem.badRequest("The request is not valid.")

        private fun statusProblem(
            status: Int,
            reason: String?,
        ): Problem {
            val resolved = HttpStatus.resolve(status)
            return when {
                resolved == null || !(resolved.is4xxClientError || resolved.is5xxServerError) -> Problem.internal()
                resolved.is4xxClientError -> Problem.forStatus(status, resolved.reasonPhrase, reason)
                else -> Problem.forStatus(status, resolved.reasonPhrase)
            }
        }
    }
}

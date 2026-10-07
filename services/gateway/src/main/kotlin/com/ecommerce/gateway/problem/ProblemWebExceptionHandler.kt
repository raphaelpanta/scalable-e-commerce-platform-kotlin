package com.ecommerce.gateway.problem

import com.ecommerce.gateway.correlation.CorrelationIds
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Before Boot's DefaultErrorWebExceptionHandler (order -1), so no default error body ever leaves the gateway. */
private const val ORDER = -2

/**
 * Renders every error the gateway itself originates (no route, 401/403, 413, 429, unreachable or slow upstreams)
 * as an RFC 9457 `application/problem+json` body with the request's `correlationId`. Details are fixed texts: no
 * exception message or upstream internals reach the client. Upstream responses, error or not, pass through as-is.
 */
@Component
@Order(ORDER)
class ProblemWebExceptionHandler(
    private val jsonMapper: JsonMapper,
) : WebExceptionHandler {
    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun handle(
        exchange: ServerWebExchange,
        ex: Throwable,
    ): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) return Mono.error(ex)
        val failure = classify(ex)
        logFailure(exchange, failure, ex)
        val correlationId =
            exchange.request.headers
                .getFirst(CorrelationIds.HEADER)
                .orEmpty()
        val body =
            linkedMapOf(
                "type" to failure.problem.type,
                "title" to failure.problem.title,
                "status" to failure.problem.status.value(),
                "detail" to failure.detail,
                "instance" to exchange.request.path.value(),
                "correlationId" to correlationId,
            )
        val bytes = jsonMapper.writeValueAsBytes(body)
        response.headers.clear()
        response.statusCode = failure.problem.status
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        response.headers.contentLength = bytes.size.toLong()
        response.headers.set(CorrelationIds.HEADER, correlationId)
        if (failure.problem == GatewayProblem.UNAUTHORIZED) {
            response.headers.set(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_BEARER)
        }
        failure.headers.forEach(response.headers::set)
        failure.cookies.forEach { cookie -> response.headers.add(HttpHeaders.SET_COOKIE, cookie) }
        return response.writeWith(Mono.fromSupplier { response.bufferFactory().wrap(bytes) })
    }

    private fun logFailure(
        exchange: ServerWebExchange,
        failure: GatewayProblemException,
        ex: Throwable,
    ) {
        if (failure.problem.status.is5xxServerError) {
            log.warn(
                "{} {} answered {}: {}",
                exchange.request.method,
                exchange.request.path.value(),
                failure.problem.status.value(),
                generateSequence(ex) { it.cause }.last().javaClass.name,
            )
            log.debug("Gateway error details", ex)
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ProblemWebExceptionHandler::class.java)
        private const val WWW_AUTHENTICATE_BEARER = "Bearer"

        /** Maps any error to the problem the client receives. */
        fun classify(error: Throwable): GatewayProblemException =
            generateSequence(error) { it.cause }
                .take(MAX_CAUSE_DEPTH)
                .firstNotNullOfOrNull(::problemOf)
                ?: GatewayProblemException(GatewayProblem.INTERNAL, cause = error)

        private fun problemOf(error: Throwable): GatewayProblemException? =
            when (error) {
                is GatewayProblemException -> {
                    error
                }

                is ResponseStatusException -> {
                    fromStatus(error.statusCode.value())?.let(::GatewayProblemException)
                }

                is ConnectException, is UnknownHostException, is NoRouteToHostException -> {
                    GatewayProblemException(GatewayProblem.UNAVAILABLE)
                }

                is TimeoutException -> {
                    GatewayProblemException(GatewayProblem.GATEWAY_TIMEOUT)
                }

                is IOException -> {
                    GatewayProblemException(GatewayProblem.BAD_GATEWAY)
                }

                else -> {
                    null
                }
            }

        @Suppress("CyclomaticComplexMethod") // a flat status table
        private fun fromStatus(status: Int): GatewayProblem? =
            when (status) {
                HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED -> GatewayProblem.NOT_FOUND
                HttpStatus.UNAUTHORIZED -> GatewayProblem.UNAUTHORIZED
                HttpStatus.FORBIDDEN -> GatewayProblem.FORBIDDEN
                HttpStatus.CONTENT_TOO_LARGE -> GatewayProblem.PAYLOAD_TOO_LARGE
                HttpStatus.TOO_MANY_REQUESTS -> GatewayProblem.THROTTLED
                HttpStatus.BAD_GATEWAY -> GatewayProblem.BAD_GATEWAY
                HttpStatus.SERVICE_UNAVAILABLE -> GatewayProblem.UNAVAILABLE
                HttpStatus.GATEWAY_TIMEOUT -> GatewayProblem.GATEWAY_TIMEOUT
                in HttpStatus.CLIENT_ERRORS -> GatewayProblem.BAD_REQUEST
                in HttpStatus.SERVER_ERRORS -> GatewayProblem.INTERNAL
                else -> null
            }

        private const val MAX_CAUSE_DEPTH = 8
    }

    /** Numeric statuses, so the table above reads like the HTTP specification. */
    private object HttpStatus {
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val CONTENT_TOO_LARGE = 413
        const val TOO_MANY_REQUESTS = 429
        const val BAD_GATEWAY = 502
        const val SERVICE_UNAVAILABLE = 503
        const val GATEWAY_TIMEOUT = 504
        val CLIENT_ERRORS = 400..499
        val SERVER_ERRORS = 500..599
    }
}

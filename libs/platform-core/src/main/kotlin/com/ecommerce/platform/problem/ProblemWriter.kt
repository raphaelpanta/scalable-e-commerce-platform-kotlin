package com.ecommerce.platform.problem

import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.correlation.CorrelationIds
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

/**
 * Writes a [Problem] straight to an exchange as `application/problem+json` with the request's correlation id and,
 * when the problem has none, the request path as `instance`. Used by the exception handler and by the security
 * entry points, which run outside the handler's codecs. (Reactive return types are inferred, as `Mono<Void>`.)
 */
class ProblemWriter(
    private val jsonMapper: JsonMapper,
) {
    /** Writes [problem] (plus [headers]) unless the response is already committed. */
    fun write(
        exchange: ServerWebExchange,
        problem: Problem,
        headers: Map<String, String> = emptyMap(),
    ) = if (exchange.response.isCommitted) exchange.response.setComplete() else render(exchange, problem, headers)

    private fun render(
        exchange: ServerWebExchange,
        problem: Problem,
        headers: Map<String, String>,
    ) = exchange.response.let { response ->
        val body = enrich(problem, exchange)
        response.statusCode = HttpStatusCode.valueOf(body.status)
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        response.headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE)
        headers.forEach { (name, value) -> response.headers.set(name, value) }
        val bytes = jsonMapper.writeValueAsBytes(body.toJsonMembers())
        response.writeWith(Mono.fromSupplier { response.bufferFactory().wrap(bytes) })
    }

    companion object {
        private const val NO_STORE = "no-store"

        /** [problem] with the correlation id and default `instance` of [exchange]. */
        fun enrich(
            problem: Problem,
            exchange: ServerWebExchange,
        ): Problem =
            problem
                .withCorrelationId(problem.correlationId ?: CorrelationIds.from(exchange))
                .withDefaultInstance(exchange.request.path.value())
    }
}

package com.ecommerce.platform.security

import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.values.SecretToken
import com.ecommerce.platform.problem.ProblemWriter
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Guards the service-to-service endpoints under `/internal/` (never routed by the gateway): the request must carry
 * `X-Internal-Token` equal to `platform.security.internal-token`, compared in constant time; otherwise it is
 * answered with a 401 `unauthorized` problem. A blank configured token rejects every internal call.
 */
class InternalTokenWebFilter(
    private val expectedToken: String,
    private val writer: ProblemWriter,
) : WebFilter {
    // Return type inferred: WebFilter's Java signature is Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ) = if (!INTERNAL.matches(exchange.request.path.pathWithinApplication()) || accepted(exchange)) {
        chain.filter(exchange)
    } else {
        writer.write(exchange, Problem.unauthorized("A valid internal token is required."))
    }

    private fun accepted(exchange: ServerWebExchange): Boolean {
        val presented = exchange.request.headers.getFirst(HEADER) ?: return false
        return expectedToken.isNotBlank() && SecretToken.constantTimeEquals(presented, expectedToken)
    }

    companion object {
        /** Header carrying the shared internal token. */
        const val HEADER: String = "X-Internal-Token"

        /** Path pattern of the internal endpoints. */
        const val PATTERN: String = "/internal/**"
        private val INTERNAL = PathPatternParser.defaultInstance.parse(PATTERN)
    }
}

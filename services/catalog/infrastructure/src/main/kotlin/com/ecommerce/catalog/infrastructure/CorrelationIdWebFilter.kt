package com.ecommerce.catalog.infrastructure

import io.micrometer.context.ContextRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.util.context.Context
import java.util.UUID

/**
 * Accepts the caller's `X-Correlation-Id` (or generates a UUID when it is missing, longer than 64 characters
 * or not made of letters, digits and hyphens), echoes it in the response and keeps it in the Reactor context.
 * A registered thread-local accessor mirrors it into the MDC, so every log line of the request carries it.
 */
@Component
class CorrelationIdWebFilter :
    WebFilter,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    // Return type inferred: WebFilter's Java signature is Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ) = correlationIdOf(exchange).let { correlationId ->
        exchange.response.headers.set(HEADER, correlationId)
        chain
            .filter(exchange)
            .doOnSuccess {
                log.info(
                    "{} {} {}",
                    exchange.request.method,
                    exchange.request.path.value(),
                    exchange.response.statusCode?.value(),
                )
            }.contextWrite(Context.of(CONTEXT_KEY, correlationId))
    }

    private fun correlationIdOf(exchange: ServerWebExchange): String =
        exchange.request.headers
            .getFirst(HEADER)
            ?.takeIf(::isAcceptable) ?: newId()

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val CONTEXT_KEY = "correlationId"
        private const val MAX_LENGTH = 64
        private val ACCEPTED = Regex("[A-Za-z0-9-]+")
        private val log: Logger = LoggerFactory.getLogger(CorrelationIdWebFilter::class.java)

        init {
            ContextRegistry.getInstance().registerThreadLocalAccessor<String>(
                CONTEXT_KEY,
                { MDC.get(CONTEXT_KEY) },
                { value -> MDC.put(CONTEXT_KEY, value) },
                { MDC.remove(CONTEXT_KEY) },
            )
        }

        private fun isAcceptable(candidate: String): Boolean =
            candidate.length <= MAX_LENGTH && ACCEPTED.matches(candidate)

        private fun newId(): String = UUID.randomUUID().toString()
    }
}

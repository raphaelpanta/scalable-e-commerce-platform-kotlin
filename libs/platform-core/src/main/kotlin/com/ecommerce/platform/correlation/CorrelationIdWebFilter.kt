package com.ecommerce.platform.correlation

import com.ecommerce.platform.core.values.CorrelationId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * First filter of every service (FR-025, contracts/gateway-routes.md "Correlation id"): reads `X-Correlation-Id`,
 * keeps it when it is 1 to 64 characters of `[A-Za-z0-9-]` ([CorrelationId.sanitise]) and otherwise generates a UUID.
 *
 * This is the second layer of the rule of research.md §3: the gateway, the only public entry, already accepts only a
 * UUID or 16 to 64 characters of `[A-Za-z0-9-]` and replaces anything else, so every request routed by it arrives here
 * with an acceptable value; the wider 1-64 range of the services only matters for calls that reach a service directly
 * (internal service-to-service calls, tests), which may use a shorter id. The filter then
 * - replaces the request header with the sanitised value and stores it in the exchange attribute
 *   [CorrelationIds.ATTRIBUTE];
 * - echoes it in the response header (also on errors written by the problem handler);
 * - writes it to the Reactor context, from which the MDC and the OpenTelemetry baggage are restored;
 * - logs one access line per request (method, path, status; never headers or bodies), with
 *   `originalCorrelationId` (log-safe, truncated) when an inbound value was replaced.
 */
class CorrelationIdWebFilter :
    WebFilter,
    Ordered {
    init {
        CorrelationContext.register()
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    // Return type inferred: WebFilter's Java signature is Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ) = run {
        val sanitised = CorrelationId.sanitise(exchange.request.headers.getFirst(CorrelationIds.HEADER))
        val id = sanitised.value.value
        val request =
            exchange.request
                .mutate()
                .headers { it.set(CorrelationIds.HEADER, id) }
                .build()
        val mutated = exchange.mutate().request(request).build()
        mutated.attributes[CorrelationIds.ATTRIBUTE] = id
        mutated.response.headers.set(CorrelationIds.HEADER, id)
        mutated.response.beforeCommit {
            Mono.fromRunnable {
                mutated.response.headers.set(CorrelationIds.HEADER, id)
                logAccess(mutated, id, sanitised.original)
            }
        }
        chain
            .filter(mutated)
            .contextWrite { context -> CorrelationIds.bind(context, id) }
    }

    private fun logAccess(
        exchange: ServerWebExchange,
        id: String,
        original: String?,
    ) {
        if (!log.isInfoEnabled) return
        MDC.putCloseable(CorrelationIds.MDC_KEY, id).use {
            val event =
                log.atInfo().let { line ->
                    if (original ==
                        null
                    ) {
                        line
                    } else {
                        line.addKeyValue(ORIGINAL, original)
                    }
                }
            event.log(
                "{} {} {}",
                exchange.request.method,
                exchange.request.path.value(),
                exchange.response.statusCode?.value(),
            )
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(CorrelationIdWebFilter::class.java)
        const val ORIGINAL = CorrelationIds.ORIGINAL_LOG_KEY
    }
}

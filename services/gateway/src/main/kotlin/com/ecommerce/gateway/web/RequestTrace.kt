package com.ecommerce.gateway.web

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** The trace and span ids of a request's server span. */
data class TraceIds(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean,
)

/**
 * Carries the trace of a request's server observation out to the access-log line (FR-025).
 *
 * [EdgeHttpHandlerDecorator] wraps the public `HttpHandler` from outside, so its access line is written after
 * `HttpWebHandlerAdapter` has stopped the `http.server.requests` observation and outside its scope: the MDC holds no
 * `traceId` there. The decorator therefore puts one [RequestTrace] per request into the Reactor context,
 * [TraceCaptureWebFilter] (the first web filter, inside the observation) copies the ids of the server span into it,
 * and [inScope] makes them current again while the access line is written.
 */
class RequestTrace {
    @Volatile
    var ids: TraceIds? = null
        private set

    /** Copies the server span of the observation that `HttpWebHandlerAdapter` keeps in the exchange [attributes]. */
    fun capture(attributes: Map<String, Any>) {
        val observation = ServerRequestObservationContext.findCurrent(attributes).orElse(null) ?: return
        val tracing = observation.get<TracingContext>(TracingContext::class.java)
        val context = tracing?.span?.context() ?: return
        ids = TraceIds(context.traceId(), context.spanId(), context.sampled() == true)
    }

    /**
     * Runs [block] with `traceId` and `spanId` in the MDC (the ECS console line) and the span context current (the
     * trace context of the OTLP log record); without a captured trace [block] runs unchanged.
     */
    fun <T> inScope(block: () -> T): T {
        val trace = ids ?: return block()
        val flags = if (trace.sampled) TraceFlags.getSampled() else TraceFlags.getDefault()
        val spanContext = SpanContext.create(trace.traceId, trace.spanId, flags, TraceState.getDefault())
        return Context.current().with(Span.wrap(spanContext)).makeCurrent().use { _ ->
            MDC.putCloseable(TRACE_ID, trace.traceId).use { _ ->
                MDC.putCloseable(SPAN_ID, trace.spanId).use { _ -> block() }
            }
        }
    }

    companion object {
        /** Reactor context key of the request's [RequestTrace]. */
        val CONTEXT_KEY: String = RequestTrace::class.java.name

        /** The MDC keys Micrometer Tracing uses, so the access line reads like every other log line. */
        const val TRACE_ID = "traceId"
        const val SPAN_ID = "spanId"
    }
}

/** First web filter of the chain: copies the server span's ids into the request's [RequestTrace]. */
@Component
class TraceCaptureWebFilter :
    WebFilter,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> =
        Mono.deferContextual { context ->
            context.getOrEmpty<RequestTrace>(RequestTrace.CONTEXT_KEY).ifPresent { it.capture(exchange.attributes) }
            chain.filter(exchange)
        }
}

package com.ecommerce.platform.observability

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter

/**
 * The W3C `traceparent` of the current span (FR-025, research §3), for carriers the tracing instrumentation does not
 * cover: the transactional outbox stores it with each event, and the relay sends it as a Kafka record header, so the
 * consumer's listener observation (Spring Kafka, `observationEnabled`) continues the producer's trace even though the
 * record is sent later by the relay job, outside any request span.
 */
object Traceparents {
    /** Header name of the W3C Trace Context. */
    const val HEADER: String = "traceparent"

    private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier?.put(key, value) }

    /**
     * The `traceparent` of the span current for the calling coroutine: its Reactor context (the request's or the
     * listener's observation) is restored into the thread locals first ([withReactorThreadLocals]), so the answer does
     * not depend on the thread the coroutine resumed on. Null when no valid span is current (tracing disabled).
     */
    suspend fun current(): String? = withReactorThreadLocals { ofCurrentThread() }

    /** The `traceparent` of the OpenTelemetry span current on this thread, or null when none is valid. */
    fun ofCurrentThread(): String? {
        val carrier = HashMap<String, String>()
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, setter)
        return carrier[HEADER]
    }
}

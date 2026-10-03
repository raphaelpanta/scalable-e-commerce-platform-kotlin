package com.ecommerce.platform.correlation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.slf4j.MDC
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.ServerWebExchange
import reactor.util.context.Context
import reactor.util.context.ContextView

/**
 * Where the correlation id of the current request lives and how to read it. [CorrelationIdWebFilter] writes it to
 * the response header, the exchange attribute [ATTRIBUTE], the Reactor context under [CONTEXT_KEY] (mirrored into
 * the MDC key [MDC_KEY] by a context-propagation accessor) and, when the OpenTelemetry API is present, the baggage
 * entry [BAGGAGE_KEY]. Clients copy it to downstream calls and events ([com.ecommerce.platform.http.WebClientDefaults]
 * does it for internal WebClients).
 */
object CorrelationIds {
    /** Request and response header. */
    const val HEADER: String = "X-Correlation-Id"

    /** Reactor context key, also the context-propagation key of the MDC accessor. */
    const val CONTEXT_KEY: String = "correlationId"

    /** MDC key, rendered as `correlationId` in the ECS log lines. */
    const val MDC_KEY: String = "correlationId"

    /** OpenTelemetry baggage entry. */
    const val BAGGAGE_KEY: String = "correlationId"

    /** Reactor context key of the OpenTelemetry baggage accessor. */
    const val BAGGAGE_CONTEXT_KEY: String = "platform.correlation.baggage"

    /** Access-log key-value of a replaced inbound value. */
    const val ORIGINAL_LOG_KEY: String = "originalCorrelationId"

    /** Exchange attribute holding the sanitised value (also readable outside the Reactor context). */
    val ATTRIBUTE: String = CorrelationIds::class.java.name + ".value"

    /**
     * The correlation id of the request being handled, from a suspending handler or client: the Reactor context of
     * the coroutine, falling back to the MDC.
     */
    suspend fun current(): String? = currentCoroutineContext()[ReactorContext]?.context?.let(::from) ?: MDC.get(MDC_KEY)

    /**
     * [context] carrying [id] the way [CorrelationIdWebFilter] writes it: under [CONTEXT_KEY] (restored into the MDC)
     * and, when the OpenTelemetry API is present, under [BAGGAGE_CONTEXT_KEY] (restored into the baggage). Event
     * listeners use it to bind an envelope's `correlationId` around the handler.
     */
    fun bind(
        context: Context,
        id: String,
    ): Context {
        CorrelationContext.register()
        val withId = context.put(CONTEXT_KEY, id)
        return if (CorrelationContext.baggageSupported) withId.put(BAGGAGE_CONTEXT_KEY, id) else withId
    }

    /** The correlation id stored in a Reactor [context]. */
    fun from(context: ContextView): String? = context.getOrDefault<String>(CONTEXT_KEY, null)

    /** The correlation id of an [exchange] (set by [CorrelationIdWebFilter]). */
    fun from(exchange: ServerWebExchange): String? = exchange.getAttribute<String>(ATTRIBUTE)

    /** The correlation id of a functional-endpoint [request]. */
    fun from(request: ServerRequest): String? = from(request.exchange())
}

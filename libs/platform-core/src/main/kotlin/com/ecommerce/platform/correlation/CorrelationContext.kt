package com.ecommerce.platform.correlation

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Scope
import org.slf4j.MDC
import org.springframework.util.ClassUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers the context-propagation accessors that restore the correlation id from the Reactor context into thread
 * locals whenever Reactor or Spring restores context (`spring.reactor.context-propagation=auto`): always the MDC,
 * and the OpenTelemetry baggage when `io.opentelemetry.api.baggage.Baggage` is on the class path.
 */
internal object CorrelationContext {
    private const val BAGGAGE_CLASS = "io.opentelemetry.api.baggage.Baggage"
    private val registered = AtomicBoolean(false)

    /** True when the OpenTelemetry API is on the class path. */
    val baggageSupported: Boolean = ClassUtils.isPresent(BAGGAGE_CLASS, CorrelationContext::class.java.classLoader)

    /** Idempotent: registers the accessors once per class loader. */
    fun register() {
        if (registered.compareAndSet(false, true)) {
            val registry = ContextRegistry.getInstance()
            registry.registerThreadLocalAccessor(MdcAccessor)
            if (baggageSupported) registry.registerThreadLocalAccessor(OtelBaggageAccessor())
        }
    }

    /** Mirrors [CorrelationIds.CONTEXT_KEY] into the MDC. */
    private object MdcAccessor : ThreadLocalAccessor<String> {
        override fun key(): Any = CorrelationIds.CONTEXT_KEY

        override fun getValue(): String? = MDC.get(CorrelationIds.MDC_KEY)

        override fun setValue(value: String) = MDC.put(CorrelationIds.MDC_KEY, value)

        override fun setValue() = MDC.remove(CorrelationIds.MDC_KEY)
    }
}

/**
 * Makes the correlation id current OpenTelemetry baggage while a Reactor context carrying
 * [CorrelationIds.BAGGAGE_CONTEXT_KEY] is restored on a thread. Every `setValue` opens an OTel scope that the
 * matching `restore` closes, in LIFO order on the same thread, so scopes never leak across requests.
 */
internal class OtelBaggageAccessor : ThreadLocalAccessor<String> {
    private val scopes = ThreadLocal.withInitial { ArrayDeque<Scope>() }

    override fun key(): Any = CorrelationIds.BAGGAGE_CONTEXT_KEY

    override fun getValue(): String? = Baggage.current().getEntryValue(CorrelationIds.BAGGAGE_KEY)

    override fun setValue(value: String) {
        push(
            Baggage
                .current()
                .toBuilder()
                .put(CorrelationIds.BAGGAGE_KEY, value)
                .build()
                .makeCurrent(),
        )
    }

    override fun setValue() {
        push(
            Baggage
                .current()
                .toBuilder()
                .remove(CorrelationIds.BAGGAGE_KEY)
                .build()
                .makeCurrent(),
        )
    }

    override fun restore(previousValue: String) = pop()

    override fun restore() = pop()

    private fun push(scope: Scope) = scopes.get().addLast(scope)

    private fun pop() {
        scopes.get().removeLastOrNull()?.close()
    }
}

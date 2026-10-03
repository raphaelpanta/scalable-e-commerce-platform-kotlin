package com.ecommerce.platform.observability

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ContextSnapshot.Scope
import io.micrometer.context.ContextSnapshotFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import reactor.util.context.ContextView
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that, every time the coroutine runs on a thread, makes the values of its Reactor context
 * ([ReactorContext]) current in the registered thread locals (context-propagation): the current Micrometer
 * observation (`ObservationThreadLocalAccessor`, hence the tracing span), the correlation id in the MDC and the
 * OpenTelemetry baggage; it restores the previous values when the coroutine suspends or completes. Keys absent from
 * the Reactor context leave their thread local untouched.
 *
 * Why (T144): a suspending handler keeps the request's observation in its Reactor context, but after it resumed from a
 * Reactor signal on another thread (an R2DBC query, `Mono.delay`, ...) the thread locals hold whatever that thread
 * restored, typically an empty `NullObservation`. Spring's coroutine WebClient extensions (`awaitExchange`,
 * `awaitBody`, ...) capture the thread locals and let them override the Reactor context, so the client observation got
 * a parent without a span and the call started a new trace. Running the call inside [withReactorThreadLocals] makes
 * the captured values the request's own, so the outbound `traceparent` continues the inbound trace.
 */
class ReactorThreadLocals :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<Scope> {
    override fun updateThreadContext(context: CoroutineContext): Scope =
        context[ReactorContext]?.context?.let { snapshots.setThreadLocalsFrom<ContextView>(it) } ?: NOTHING_SET

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Scope,
    ) = oldState.close()

    override fun toString(): String = "ReactorThreadLocals"

    /** Key and single instance of the element. */
    companion object Key : CoroutineContext.Key<ReactorThreadLocals> {
        /** The element (stateless: it reads the coroutine's Reactor context on every resumption). */
        val INSTANCE: ReactorThreadLocals = ReactorThreadLocals()

        private val snapshots: ContextSnapshotFactory =
            ContextSnapshotFactory.builder().contextRegistry(ContextRegistry.getInstance()).build()

        private val NOTHING_SET = Scope { }
    }
}

/**
 * Runs [block] with [ReactorThreadLocals]: the Reactor context of the calling coroutine (the request's observation,
 * correlation id and baggage) is current in the thread locals whenever [block] runs, on whatever thread it resumes.
 * Used by [com.ecommerce.platform.http.awaitBodyOrProblem]; wrap any other Reactor-bridged call made from a suspending
 * handler (a `WebClient` used with Spring's own `await*` extensions, for example) the same way.
 */
suspend fun <T> withReactorThreadLocals(block: suspend CoroutineScope.() -> T): T =
    withContext(ReactorThreadLocals.INSTANCE, block)

package com.ecommerce.platform.observability

import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * Spring's [coRouter] whose handlers (and `filter`/`onError` functions) run with [ReactorThreadLocals] (T179, FR-025,
 * SC-007): the request's Reactor context, where the [com.ecommerce.platform.correlation.CorrelationIdWebFilter] put the
 * correlation id and WebFlux the server observation, is restored into the thread locals every time the handler's
 * coroutine resumes. Every log line of a handler and of the use cases it calls therefore carries `correlationId`,
 * `traceId` and `spanId` in the MDC, also after a Reactor hop (an R2DBC query, `delay`, another dispatcher), and the
 * events the handler writes to the outbox record the request's `traceparent`.
 *
 * Every service builds its routes with it instead of [coRouter]. The context is set before [routes] runs, so nested
 * routers (`"/prefix".nest { ... }`) inherit it.
 */
fun observedCoRouter(routes: CoRouterFunctionDsl.() -> Unit): RouterFunction<ServerResponse> =
    coRouter {
        context { ReactorThreadLocals.INSTANCE }
        routes()
    }

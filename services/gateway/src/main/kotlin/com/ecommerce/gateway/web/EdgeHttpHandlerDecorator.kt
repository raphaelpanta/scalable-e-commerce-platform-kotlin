package com.ecommerce.gateway.web

import com.ecommerce.gateway.browser.Transport
import com.ecommerce.gateway.correlation.CorrelationIds
import io.micrometer.context.ContextRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.util.context.Context
import java.util.concurrent.TimeUnit

/**
 * Outermost layer of the public port, around the WebFlux exception handlers, so that it also sees the gateway's own
 * error answers:
 *
 * - sanitises `X-Correlation-Id` ([CorrelationIds]), forwards it upstream, echoes it on every response and keeps
 *   it in the Reactor context (mirrored into the MDC, so every log line of the request carries `correlationId`);
 * - drops client-supplied identity, internal and forwarding headers ([EdgeHeaders.CLIENT_FORBIDDEN]);
 * - adds the security headers and removes server details from every response;
 * - writes one structured access-log line per request, with `originalCorrelationId` when the client's value was
 *   refused, and the `traceId` and `spanId` of the request's server span ([RequestTrace]): the line is written after
 *   the server observation has stopped, so the span is captured inside it and made current again for the line.
 */
@Component
class EdgeHttpHandlerDecorator : HttpHandlerDecoratorFactory {
    override fun apply(delegate: HttpHandler): HttpHandler =
        HttpHandler { request, response -> handle(delegate, request, response) }

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    private fun handle(
        delegate: HttpHandler,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Mono<Void> {
        val started = System.nanoTime()
        val correlation = CorrelationIds.resolve(request.headers.getFirst(CorrelationIds.HEADER))
        val trace = RequestTrace()
        val transport = Transport.of(request.uri.scheme, request.headers.getFirst(Transport.FORWARDED_PROTO))
        val sanitised =
            request
                .mutate()
                .headers { headers ->
                    EdgeHeaders.stripClientSupplied(headers)
                    headers.set(CorrelationIds.HEADER, correlation.id)
                }.apply {
                    // `X-Forwarded-Proto: https` (a TLS terminator in front) is kept as the request scheme, so the
                    // browser-session filters pick the `__Host-` cookie names after the header itself is dropped.
                    if (transport == Transport.HTTPS &&
                        !request.uri.scheme.equals(Transport.HTTPS_SCHEME, ignoreCase = true)
                    ) {
                        uri(
                            UriComponentsBuilder
                                .fromUri(request.uri)
                                .scheme(Transport.HTTPS_SCHEME)
                                .build(true)
                                .toUri(),
                        )
                    }
                }.build()
        response.headers.set(CorrelationIds.HEADER, correlation.id)
        response.beforeCommit {
            EdgeHeaders.harden(response.headers)
            response.headers.set(CorrelationIds.HEADER, correlation.id)
            Mono.empty()
        }
        return delegate
            .handle(sanitised, response)
            .doFinally { trace.inScope { accessLog(sanitised, response, correlation, started) } }
            .contextWrite(Context.of(CorrelationIds.CONTEXT_KEY, correlation.id, RequestTrace.CONTEXT_KEY, trace))
    }

    private fun accessLog(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        correlation: CorrelationIds.Resolution,
        started: Long,
    ) {
        MDC.putCloseable(CorrelationIds.CONTEXT_KEY, correlation.id).use { _ ->
            var event =
                log
                    .atInfo()
                    .addKeyValue("method", request.method.name())
                    .addKeyValue("path", request.path.value())
                    .addKeyValue("status", response.statusCode?.value())
                    .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            if (correlation.original != null) {
                event = event.addKeyValue(CorrelationIds.ORIGINAL_LOG_FIELD, correlation.original)
            }
            event.log("{} {} {}", request.method, request.path.value(), response.statusCode?.value())
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger("com.ecommerce.gateway.access")

        init {
            ContextRegistry.getInstance().registerThreadLocalAccessor<String>(
                CorrelationIds.CONTEXT_KEY,
                { MDC.get(CorrelationIds.CONTEXT_KEY) },
                { value -> MDC.put(CorrelationIds.CONTEXT_KEY, value) },
                { MDC.remove(CorrelationIds.CONTEXT_KEY) },
            )
        }
    }
}

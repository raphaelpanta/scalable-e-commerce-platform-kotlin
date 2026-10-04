package com.ecommerce.platform.observability

import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.testapp.PlatformWebTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.MDC
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * T179 (FR-025, SC-007): a handler routed by [observedCoRouter] finds the request's `correlationId` and `traceId` in
 * the MDC (hence in its log lines and those of the use cases it calls) after its coroutine resumed on another thread:
 * after `delay`, after a Reactor signal from another scheduler and on another dispatcher.
 *
 * Tracing is enabled (OTLP export off) so that the server observation carries a span; the request continues a trace
 * started upstream, so the expected trace id is known.
 */
@Import(ObservedCoRouterTest.Routes::class)
@TestPropertySource(
    properties = ["management.tracing.export.enabled=true", "management.tracing.export.otlp.enabled=false"],
)
class ObservedCoRouterTest(
    @LocalServerPort port: Int,
) : PlatformWebTest(port) {
    @ParameterizedTest
    @ValueSource(strings = [DIRECT, AFTER_DELAY, AFTER_REACTOR_HOP, OTHER_DISPATCHER])
    fun `the MDC of the handler carries the correlation and trace ids after a hop`(mode: String) {
        val seen =
            client
                .get()
                .uri("/api/v1/public/mdc/{mode}", mode)
                .header(CorrelationIds.HEADER, CORRELATION_ID)
                .header(TRACEPARENT, "00-$UPSTREAM_TRACE-$UPSTREAM_SPAN-01")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(object : ParameterizedTypeReference<Map<String, String?>>() {})
                .returnResult()
                .responseBody

        seen shouldBe mapOf(CorrelationIds.MDC_KEY to CORRELATION_ID, TRACE_ID to UPSTREAM_TRACE)
    }

    /** One route that reads the MDC after the coroutine changed threads in the way named by the path. */
    @TestConfiguration(proxyBeanMethods = false)
    class Routes {
        @Bean
        @Suppress("InjectDispatcher") // the test deliberately hops to another dispatcher to prove the MDC survives it
        fun mdcRoutes() =
            observedCoRouter {
                GET("/api/v1/public/mdc/{mode}") { request ->
                    when (request.pathVariable("mode")) {
                        AFTER_DELAY -> delay(HOP_MILLIS)
                        AFTER_REACTOR_HOP -> Mono.delay(Duration.ofMillis(HOP_MILLIS)).awaitSingle()
                        OTHER_DISPATCHER -> withContext(Dispatchers.IO) { delay(HOP_MILLIS) }
                        else -> Unit
                    }
                    ServerResponse.ok().bodyValueAndAwait(
                        mapOf(
                            CorrelationIds.MDC_KEY to MDC.get(CorrelationIds.MDC_KEY),
                            TRACE_ID to MDC.get(TRACE_ID),
                        ),
                    )
                }
            }
    }

    private companion object {
        const val DIRECT = "direct"
        const val AFTER_DELAY = "after-delay"
        const val AFTER_REACTOR_HOP = "after-reactor-hop"
        const val OTHER_DISPATCHER = "other-dispatcher"
        const val HOP_MILLIS = 5L
        const val TRACE_ID = "traceId"
        const val TRACEPARENT = "traceparent"
        const val CORRELATION_ID = "corr-t179-observed-router"
        const val UPSTREAM_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val UPSTREAM_SPAN = "00f067aa0ba902b7"
    }
}

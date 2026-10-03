package com.ecommerce.platform.http

import com.ecommerce.platform.testapp.PlatformWebTest
import com.ecommerce.platform.testing.InternalToken
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * T144 (FR-025, SC-007): an internal call made from a suspending handler continues the trace of the inbound request.
 * The handler builds its client with [WebClientDefaults.internalClient] on Boot's auto-configured `WebClient.Builder`
 * and calls a WireMock upstream, directly or after the coroutine changed threads; the outbound `traceparent` must name
 * the trace of the inbound server span and the client span as its parent.
 *
 * Boot only installs the W3C propagator when tracing export is enabled, so the test enables it and switches the OTLP
 * exporter off; spans are captured by an in-memory exporter instead.
 */
@Import(TracePropagationTest.Upstream::class)
@TestPropertySource(
    properties = ["management.tracing.export.enabled=true", "management.tracing.export.otlp.enabled=false"],
)
class TracePropagationTest(
    @LocalServerPort port: Int,
) : PlatformWebTest(port) {
    @BeforeEach
    fun reset() {
        spans.reset()
        upstream.resetAll()
        upstream.stubFor(get(urlPathMatching("/internal/products/.+/pricing")).willReturn(okJson("""{"price":1}""")))
    }

    @ParameterizedTest
    @ValueSource(strings = [DIRECT, AFTER_DELAY, OTHER_DISPATCHER, AFTER_REACTOR_HOP, NESTED_MONO])
    fun `the outbound traceparent carries the trace of the inbound server span`(mode: String) {
        call(mode)
        val outbound = outboundTraceparent()
        val server = serverSpan()
        val client = spans.finishedSpanItems.single { it.kind == SpanKind.CLIENT }
        outbound.traceId shouldBe server.traceId
        outbound.parentId shouldBe client.spanId
        client.traceId shouldBe server.traceId
        ancestorsOf(client) shouldContain server.spanId
    }

    @ParameterizedTest
    @ValueSource(strings = [DIRECT, OTHER_DISPATCHER])
    fun `a trace started upstream by the gateway reaches the internal call`(mode: String) {
        call(mode, traceparent = "00-$GATEWAY_TRACE-$GATEWAY_SPAN-01")
        val server = serverSpan()
        server.traceId shouldBe GATEWAY_TRACE
        server.parentSpanId shouldBe GATEWAY_SPAN
        val outbound = outboundTraceparent()
        outbound.traceId shouldBe GATEWAY_TRACE
        outbound.parentId shouldNotBe GATEWAY_SPAN
    }

    private fun call(
        mode: String,
        traceparent: String? = null,
    ) {
        client
            .get()
            .uri("/api/v1/public/trace/{mode}", mode)
            .headers { headers -> traceparent?.let { headers.set(TRACEPARENT, it) } }
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .isEqualTo("""{"price":1}""")
    }

    private fun outboundTraceparent(): Traceparent {
        val header =
            upstream.allServeEvents
                .single()
                .request
                .getHeader(TRACEPARENT)
        header.shouldNotBeNull()
        val fields = header.split("-")
        fields.first() shouldBe "00"
        return Traceparent(traceId = fields[1], parentId = fields[2])
    }

    /** The server span ends once the response is written, which can be just after the client has read it. */
    private fun serverSpan(): SpanData {
        await().atMost(Duration.ofSeconds(5)).until { spans.finishedSpanItems.any { it.kind == SpanKind.SERVER } }
        return spans.finishedSpanItems.single { it.kind == SpanKind.SERVER }
    }

    /** Span ids from the parent of [span] up to the root (security observations sit between server and client). */
    private fun ancestorsOf(span: SpanData): List<String> {
        val byId = spans.finishedSpanItems.associateBy { it.spanId }
        val ancestors = generateSequence(byId[span.parentSpanId]) { byId[it.parentSpanId] }
        return ancestors.map { it.spanId }.toList()
    }

    private data class Traceparent(
        val traceId: String,
        val parentId: String,
    )

    /** A route calling the WireMock upstream directly or after the handler's coroutine moved to another thread. */
    @TestConfiguration(proxyBeanMethods = false)
    class Upstream {
        @Bean
        fun inMemorySpanProcessor(): SpanProcessor = SimpleSpanProcessor.create(spans)

        @Bean
        fun traceRoutes(builder: WebClient.Builder) =
            WebClientDefaults.internalClient(builder, upstream.baseUrl(), InternalToken.TEST).let { pricing ->
                suspend fun price(): String =
                    pricing
                        .get()
                        .uri("/internal/products/{id}/pricing", "p-1")
                        .awaitBodyOrProblem<String>()
                        .getOrNull()
                        .orEmpty()
                coRouter {
                    GET("/api/v1/public/trace/{mode}") { request ->
                        val body =
                            when (request.pathVariable("mode")) {
                                AFTER_DELAY -> delay(1).let { price() }
                                OTHER_DISPATCHER -> onAnotherDispatcher { price() }
                                AFTER_REACTOR_HOP -> Mono.delay(Duration.ofMillis(1)).awaitSingle().let { price() }
                                NESTED_MONO -> mono { price() }.awaitSingle()
                                else -> price()
                            }
                        ServerResponse.ok().bodyValueAndAwait(body)
                    }
                }
            }
    }

    companion object {
        private const val TRACEPARENT = "traceparent"
        private const val DIRECT = "direct"
        private const val AFTER_DELAY = "after-delay"
        private const val OTHER_DISPATCHER = "other-dispatcher"
        private const val AFTER_REACTOR_HOP = "after-reactor-hop"
        private const val NESTED_MONO = "nested-mono"
        private const val GATEWAY_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
        private const val GATEWAY_SPAN = "00f067aa0ba902b7"

        val spans: InMemorySpanExporter = InMemorySpanExporter.create()

        suspend fun <T> onAnotherDispatcher(
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            block: suspend () -> T,
        ): T = withContext(dispatcher) { block() }

        val upstream: WireMockServer = WireMockServer(options().dynamicPort()).apply { start() }

        @JvmStatic
        @AfterAll
        fun stopUpstream() = upstream.stop()
    }
}

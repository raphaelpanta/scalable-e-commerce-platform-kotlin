package com.ecommerce.gateway.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext
import io.mockk.every
import io.mockk.mockk
import org.slf4j.MDC
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.util.context.Context
import io.opentelemetry.api.trace.Span as OtelSpan

const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
const val SPAN_ID = "00f067aa0ba902b7"

/** The exchange attributes `HttpWebHandlerAdapter` leaves for a request whose server span is [span]. */
fun observedAttributes(
    span: Span? = serverSpan(),
    tracing: Boolean = true,
): MutableMap<String, Any> {
    val attributes = mutableMapOf<String, Any>()
    val observation =
        ServerRequestObservationContext(MockServerHttpRequest.get("/").build(), MockServerHttpResponse(), attributes)
    if (tracing) observation.put(TracingContext::class.java, TracingContext().apply { this.span = span })
    attributes[ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE] = observation
    return attributes
}

fun serverSpan(sampled: Boolean? = true): Span {
    val context =
        mockk<TraceContext> {
            every { traceId() } returns TRACE_ID
            every { spanId() } returns SPAN_ID
            every { sampled() } returns sampled
        }
    return mockk { every { context() } returns context }
}

class RequestTraceTest :
    FunSpec({
        test("the server span of the observation is captured") {
            RequestTrace().apply { capture(observedAttributes()) }.ids shouldBe TraceIds(TRACE_ID, SPAN_ID, true)
            RequestTrace().apply { capture(observedAttributes(serverSpan(sampled = false))) }.ids?.sampled shouldBe
                false
            RequestTrace().apply { capture(observedAttributes(serverSpan(sampled = null))) }.ids?.sampled shouldBe false
        }

        test("nothing is captured without observation, tracing or span") {
            RequestTrace().apply { capture(emptyMap()) }.ids.shouldBeNull()
            RequestTrace().apply { capture(observedAttributes(tracing = false)) }.ids.shouldBeNull()
            RequestTrace().apply { capture(observedAttributes(span = null)) }.ids.shouldBeNull()
        }

        test("in scope, the ids are in the MDC and the span context is current; afterwards neither is") {
            val trace = RequestTrace().apply { capture(observedAttributes()) }
            val result =
                trace.inScope {
                    MDC.get(RequestTrace.TRACE_ID) shouldBe TRACE_ID
                    MDC.get(RequestTrace.SPAN_ID) shouldBe SPAN_ID
                    val current = OtelSpan.current().spanContext
                    current.traceId shouldBe TRACE_ID
                    current.spanId shouldBe SPAN_ID
                    current.isSampled.shouldBeTrue()
                    "logged"
                }
            result shouldBe "logged"
            MDC.get(RequestTrace.TRACE_ID).shouldBeNull()
            MDC.get(RequestTrace.SPAN_ID).shouldBeNull()
            OtelSpan
                .current()
                .spanContext.isValid
                .shouldBeFalse()
        }

        test("an unsampled span stays unsampled") {
            val trace = RequestTrace().apply { capture(observedAttributes(serverSpan(sampled = false))) }
            trace.inScope { OtelSpan.current().spanContext.isSampled }.shouldBeFalse()
        }

        test("without a captured trace the block runs unchanged") {
            RequestTrace()
                .inScope {
                    MDC.get(RequestTrace.TRACE_ID).shouldBeNull()
                    OtelSpan.current().spanContext.isValid
                }.shouldBeFalse()
        }

        test("the first web filter copies the server span into the request's trace and continues") {
            val filter = TraceCaptureWebFilter()
            filter.order shouldBe Int.MIN_VALUE
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
            exchange.attributes.putAll(observedAttributes())
            var continued = 0
            val chain = WebFilterChain { Mono.fromRunnable { continued++ } }
            val trace = RequestTrace()

            filter.filter(exchange, chain).contextWrite(Context.of(RequestTrace.CONTEXT_KEY, trace)).block()
            filter.filter(exchange, chain).block()

            trace.ids shouldBe TraceIds(TRACE_ID, SPAN_ID, true)
            continued shouldBe 2
        }
    })

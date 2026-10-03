package com.ecommerce.gateway.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ecommerce.gateway.correlation.CorrelationIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.context.ContextRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import java.util.UUID

private const val VALID_ID = "checkout-flow-0001-a"
private const val MAX_DURATION_MS = 60_000L

/** What the wrapped handler saw. */
private class Seen {
    var request: ServerHttpRequest? = null
    var context: ContextView? = null
    var correlationHeaderAtHandle: String? = null
}

class EdgeHttpHandlerDecoratorTest :
    FunSpec({
        val accessLogger = LoggerFactory.getLogger("com.ecommerce.gateway.access") as Logger
        val appender = ListAppender<ILoggingEvent>()

        beforeSpec {
            appender.start()
            accessLogger.addAppender(appender)
        }
        afterSpec { accessLogger.detachAppender(appender) }
        beforeTest { appender.list.clear() }

        /** Runs [request] through the decorated handler; the inner handler answers like an upstream would. */
        @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
        fun handle(
            request: MockServerHttpRequest,
            upstream: (Seen) -> Mono<Void> = { Mono.empty() },
        ): Pair<Seen, MockServerHttpResponse> {
            val seen = Seen()
            val response = MockServerHttpResponse()
            val inner =
                HttpHandler { forwarded, answer ->
                    Mono.deferContextual { context ->
                        seen.request = forwarded
                        seen.context = context
                        seen.correlationHeaderAtHandle = answer.headers.getFirst(CorrelationIds.HEADER)
                        answer.statusCode = HttpStatus.OK
                        answer.headers.set("Server", "Jetty(12)")
                        answer.headers.set(EdgeHeaders.INTERNAL_TOKEN, "leak")
                        answer.headers.set(EdgeHeaders.ACCOUNT_ID, "account")
                        answer.headers.set(CorrelationIds.HEADER, "upstream-echo-0000001")
                        upstream(seen).then(answer.setComplete())
                    }
                }
            EdgeHttpHandlerDecorator().apply(inner).handle(request, response).block()
            return seen to response
        }

        fun accessEvent(): ILoggingEvent = appender.list.single()

        fun ILoggingEvent.pairs(): Map<String, Any?> = keyValuePairs.orEmpty().associate { it.key to it.value }

        test("a refused correlation id is replaced, forwarded, echoed and logged as originalCorrelationId") {
            val (seen, response) =
                handle(
                    MockServerHttpRequest
                        .get(
                            "/api/v1/catalog/products",
                        ).header(CorrelationIds.HEADER, "abc-123")
                        .build(),
                )

            val id =
                seen.request
                    .shouldNotBeNull()
                    .headers
                    .getFirst(CorrelationIds.HEADER)
                    .shouldNotBeNull()
            UUID.fromString(id).toString() shouldBe id
            seen.correlationHeaderAtHandle shouldBe id
            response.headers.getFirst(CorrelationIds.HEADER) shouldBe id
            seen.context.shouldNotBeNull().get<String>(CorrelationIds.CONTEXT_KEY) shouldBe id
            seen.context.shouldNotBeNull().hasKey(RequestTrace.CONTEXT_KEY) shouldBe true

            val event = accessEvent()
            event.formattedMessage shouldBe "GET /api/v1/catalog/products 200"
            event.pairs() shouldContainAll
                mapOf(
                    "method" to "GET",
                    "path" to "/api/v1/catalog/products",
                    "status" to 200,
                    CorrelationIds.ORIGINAL_LOG_FIELD to "abc-123",
                )
            (event.pairs()["durationMs"] as Long) shouldBeInRange 0L..MAX_DURATION_MS
            event.mdcPropertyMap[CorrelationIds.CONTEXT_KEY] shouldBe id
            MDC.get(CorrelationIds.CONTEXT_KEY).shouldBeNull()
        }

        test("a valid correlation id is kept and nothing is recorded as original") {
            val (seen, response) =
                handle(MockServerHttpRequest.get("/api/v1/cart").header(CorrelationIds.HEADER, VALID_ID).build())
            seen.request
                .shouldNotBeNull()
                .headers
                .getFirst(CorrelationIds.HEADER) shouldBe VALID_ID
            response.headers.getFirst(CorrelationIds.HEADER) shouldBe VALID_ID
            accessEvent().pairs().containsKey(CorrelationIds.ORIGINAL_LOG_FIELD) shouldBe false
        }

        test("client-supplied identity, internal and forwarding headers never reach the upstream") {
            val request =
                MockServerHttpRequest
                    .get("/api/v1/cart")
                    .header(EdgeHeaders.ACCOUNT_ID, "forged")
                    .header(EdgeHeaders.ROLES, "operator")
                    .header("X-Forwarded-For", "203.0.113.9")
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .build()
            val forwarded =
                handle(request)
                    .first.request
                    .shouldNotBeNull()
                    .headers
            EdgeHeaders.CLIENT_FORBIDDEN.forEach { forwarded.getFirst(it).shouldBeNull() }
            listOf(EdgeHeaders.ACCOUNT_ID, EdgeHeaders.ROLES, "X-Forwarded-For").forEach { name ->
                (name in EdgeHeaders.CLIENT_FORBIDDEN) shouldBe true
            }
            forwarded.getFirst(HttpHeaders.ACCEPT) shouldBe "application/json"
        }

        test("responses carry the security headers and lose server details and internal headers") {
            val headers = handle(MockServerHttpRequest.get("/api/v1/cart").build()).second.headers
            EdgeHeaders.SECURITY.forEach { (name, value) -> headers.getFirst(name) shouldBe value }
            EdgeHeaders.RESPONSE_FORBIDDEN.forEach { headers.getFirst(it).shouldBeNull() }
            EdgeHeaders.SECURITY.keys shouldBe
                setOf(
                    "Strict-Transport-Security",
                    "X-Content-Type-Options",
                    "X-Frame-Options",
                    "Content-Security-Policy",
                    "Referrer-Policy",
                )
            EdgeHeaders.RESPONSE_FORBIDDEN shouldBe
                listOf("Server", "X-Powered-By", "X-Application-Context", "X-Account-Id", "X-Roles", "X-Internal-Token")
        }

        test("the access line carries the trace of the server span captured inside the handler") {
            handle(MockServerHttpRequest.get("/api/v1/cart").build()) { seen ->
                seen.context
                    .shouldNotBeNull()
                    .get<RequestTrace>(RequestTrace.CONTEXT_KEY)
                    .capture(observedAttributes())
                Mono.empty()
            }
            accessEvent().mdcPropertyMap shouldContainAll
                mapOf(RequestTrace.TRACE_ID to TRACE_ID, RequestTrace.SPAN_ID to SPAN_ID)
        }

        test("a failing handler is still logged once") {
            val request = MockServerHttpRequest.post("/api/v1/orders").build()
            val failure = IllegalStateException("upstream gone")
            shouldThrow<IllegalStateException> {
                EdgeHttpHandlerDecorator()
                    .apply {
                        _,
                        _,
                        ->
                        Mono.error(failure)
                    }.handle(request, MockServerHttpResponse())
                    .block()
            }
            accessEvent().pairs()["path"] shouldBe "/api/v1/orders"
        }

        test("the correlation id is restored into the MDC on every Reactor thread hop") {
            EdgeHttpHandlerDecorator() shouldNotBe null
            val accessor =
                ContextRegistry
                    .getInstance()
                    .threadLocalAccessors
                    .single { it.key() == CorrelationIds.CONTEXT_KEY }

            @Suppress("UNCHECKED_CAST")
            val correlation = accessor as io.micrometer.context.ThreadLocalAccessor<String>
            correlation.setValue(VALID_ID)
            MDC.get(CorrelationIds.CONTEXT_KEY) shouldBe VALID_ID
            correlation.getValue() shouldBe VALID_ID
            correlation.setValue()
            MDC.get(CorrelationIds.CONTEXT_KEY) shouldBe null
        }
    })

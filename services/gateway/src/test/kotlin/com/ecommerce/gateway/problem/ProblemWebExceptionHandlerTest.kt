package com.ecommerce.gateway.problem

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ecommerce.gateway.correlation.CorrelationIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

private const val CORRELATION_ID = "checkout-flow-0001-a"

/** A cause chain of [depth] wrappers around [root]. */
private fun wrapped(
    root: Throwable,
    depth: Int,
): Throwable = (1..depth).fold(root) { cause, level -> IllegalStateException("wrapper $level", cause) }

class ProblemWebExceptionHandlerTest :
    FunSpec({
        val jsonMapper = JsonMapper.builder().build()
        val handler = ProblemWebExceptionHandler(jsonMapper)
        val logger = LoggerFactory.getLogger(ProblemWebExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()

        beforeSpec {
            appender.start()
            logger.addAppender(appender)
            logger.level = Level.DEBUG
        }
        afterSpec {
            logger.detachAppender(appender)
            logger.level = null
        }
        beforeTest { appender.list.clear() }

        fun exchange() =
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders/42").header(CorrelationIds.HEADER, CORRELATION_ID),
            )

        context("errors map to the problem the client receives") {
            withData(
                nameFn = { (error, _) -> error.toString() },
                ResponseStatusException(HttpStatus.NOT_FOUND) to GatewayProblem.NOT_FOUND,
                ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED) to GatewayProblem.NOT_FOUND,
                ResponseStatusException(HttpStatus.UNAUTHORIZED) to GatewayProblem.UNAUTHORIZED,
                ResponseStatusException(HttpStatus.FORBIDDEN) to GatewayProblem.FORBIDDEN,
                ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE) to GatewayProblem.PAYLOAD_TOO_LARGE,
                ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS) to GatewayProblem.THROTTLED,
                ResponseStatusException(HttpStatus.BAD_GATEWAY) to GatewayProblem.BAD_GATEWAY,
                ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE) to GatewayProblem.UNAVAILABLE,
                ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT) to GatewayProblem.GATEWAY_TIMEOUT,
                ResponseStatusException(HttpStatus.BAD_REQUEST) to GatewayProblem.BAD_REQUEST,
                ResponseStatusException(HttpStatusCode.valueOf(499)) to GatewayProblem.BAD_REQUEST,
                ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR) to GatewayProblem.INTERNAL,
                ResponseStatusException(HttpStatusCode.valueOf(599)) to GatewayProblem.INTERNAL,
                ResponseStatusException(HttpStatus.FOUND) to GatewayProblem.INTERNAL,
                ConnectException("refused") to GatewayProblem.UNAVAILABLE,
                UnknownHostException("catalog") to GatewayProblem.UNAVAILABLE,
                NoRouteToHostException("no route") to GatewayProblem.UNAVAILABLE,
                TimeoutException("slow") to GatewayProblem.GATEWAY_TIMEOUT,
                IOException("reset") to GatewayProblem.BAD_GATEWAY,
                IllegalStateException("bug") to GatewayProblem.INTERNAL,
                wrapped(ConnectException("refused"), depth = 7) to GatewayProblem.UNAVAILABLE,
                wrapped(ConnectException("refused"), depth = 8) to GatewayProblem.INTERNAL,
                // A status decides before its cause; a status outside 4xx/5xx leaves the decision to the cause.
                ResponseStatusException(HttpStatus.BAD_REQUEST, "x", ConnectException()) to GatewayProblem.BAD_REQUEST,
                ResponseStatusException(HttpStatusCode.valueOf(499), "x", ConnectException()) to
                    GatewayProblem.BAD_REQUEST,
                ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "x", ConnectException()) to
                    GatewayProblem.INTERNAL,
                ResponseStatusException(HttpStatusCode.valueOf(599), "x", ConnectException()) to
                    GatewayProblem.INTERNAL,
                ResponseStatusException(HttpStatus.FOUND, "x", ConnectException()) to GatewayProblem.UNAVAILABLE,
                ResponseStatusException(HttpStatusCode.valueOf(600), "x", ConnectException()) to
                    GatewayProblem.UNAVAILABLE,
            ) { (error, problem) ->
                val failure = ProblemWebExceptionHandler.classify(error)
                failure.problem shouldBe problem
                failure.detail shouldBe problem.defaultDetail
                failure.headers shouldBe emptyMap()
            }
        }

        test("a gateway problem keeps its own detail and headers") {
            val throttled =
                GatewayProblemException(GatewayProblem.THROTTLED, headers = mapOf(HttpHeaders.RETRY_AFTER to "6"))
            ProblemWebExceptionHandler.classify(throttled) shouldBeSameInstanceAs throttled
            ProblemWebExceptionHandler.classify(wrapped(throttled, depth = 2)) shouldBeSameInstanceAs throttled
            throttled.message shouldBe GatewayProblem.THROTTLED.defaultDetail
        }

        test("problem types share the documented base and slugs") {
            GatewayProblem.entries.associate { it.name to it.type.removePrefix(PROBLEM_TYPE_BASE) } shouldBe
                mapOf(
                    "BAD_REQUEST" to "validation",
                    "UNAUTHORIZED" to "unauthorized",
                    "FORBIDDEN" to "forbidden",
                    "NOT_FOUND" to "not-found",
                    "PAYLOAD_TOO_LARGE" to "payload-too-large",
                    "THROTTLED" to "throttled",
                    "INTERNAL" to "internal",
                    "BAD_GATEWAY" to "unavailable",
                    "UNAVAILABLE" to "unavailable",
                    "GATEWAY_TIMEOUT" to "unavailable",
                )
            GatewayProblem.NOT_FOUND.title shouldBe "Not found"
            GatewayProblem.NOT_FOUND.defaultDetail shouldBe "No route matches this method and path."
            GatewayProblem.GATEWAY_TIMEOUT.defaultDetail shouldBe "The target service did not answer in time."
        }

        test("the answer is an RFC 9457 body with the correlation id, and nothing of the earlier response") {
            val exchange = exchange()
            exchange.response.headers.set("X-Upstream-Detail", "leak")
            val error =
                GatewayProblemException(
                    GatewayProblem.THROTTLED,
                    detail = "Slow down.",
                    headers = mapOf(HttpHeaders.RETRY_AFTER to "6"),
                )

            handler.handle(exchange, error).block()

            val response = exchange.response
            response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
            response.headers.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON
            response.headers.getFirst(CorrelationIds.HEADER) shouldBe CORRELATION_ID
            response.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBe "6"
            response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE).shouldBeNull()
            response.headers.getFirst("X-Upstream-Detail").shouldBeNull()
            val body = response.bodyAsString.block()!!
            response.headers.contentLength shouldBe body.toByteArray().size.toLong()
            jsonMapper.readValue(body, Map::class.java) shouldBe
                mapOf(
                    "type" to PROBLEM_TYPE_BASE + "throttled",
                    "title" to "Too many requests",
                    "status" to 429,
                    "detail" to "Slow down.",
                    "instance" to "/api/v1/orders/42",
                    "correlationId" to CORRELATION_ID,
                )
            appender.list.shouldBeEmpty()
        }

        test("401 answers carry WWW-Authenticate: Bearer") {
            val exchange = exchange()
            handler.handle(exchange, GatewayProblemException(GatewayProblem.UNAUTHORIZED)).block()
            exchange.response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
        }

        test("a problem's cookies become Set-Cookie headers, one each, after the earlier headers were dropped") {
            val exchange = exchange()
            exchange.response.headers.add(HttpHeaders.SET_COOKIE, "session=stale; Path=/")
            val deletions =
                listOf(
                    "__Host-session=; Max-Age=0; HttpOnly; Secure; SameSite=Strict; Path=/",
                    "session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/",
                )
            val error = GatewayProblemException(GatewayProblem.UNAUTHORIZED, cookies = deletions)
            error.cookies shouldBe deletions
            handler.handle(exchange, error).block()
            exchange.response.headers[HttpHeaders.SET_COOKIE] shouldBe deletions
            GatewayProblemException(GatewayProblem.THROTTLED).cookies shouldBe emptyList()
        }

        test("a request without correlation header still gets the field, empty") {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"))
            handler.handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()
            jsonMapper.readValue(exchange.response.bodyAsString.block(), Map::class.java)["correlationId"] shouldBe ""
        }

        test("server-side failures are logged with the root cause class, details at debug") {
            handler.handle(exchange(), wrapped(ConnectException("refused"), depth = 2)).block()
            val (warning, details) = appender.list
            warning.level shouldBe Level.WARN
            warning.formattedMessage shouldBe "GET /api/v1/orders/42 answered 503: java.net.ConnectException"
            details.level shouldBe Level.DEBUG
        }

        test("a committed response is left alone and the error propagates") {
            val exchange = exchange()
            exchange.response.setComplete().block()
            val error = IllegalStateException("late")
            shouldThrow<IllegalStateException> { handler.handle(exchange, error).block() } shouldBeSameInstanceAs error
        }
    })

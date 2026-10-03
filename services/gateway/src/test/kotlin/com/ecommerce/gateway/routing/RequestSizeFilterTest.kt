package com.ecommerce.gateway.routing

import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.util.unit.DataSize
import reactor.core.publisher.Flux

private const val LIMIT = 10L
private const val ROUTE_LIMIT = 20L

private fun chunks(vararg sizes: Int): Flux<DataBuffer> =
    Flux.fromIterable(sizes.map { DefaultDataBufferFactory.sharedInstance.wrap(ByteArray(it)) })

class RequestSizeFilterTest :
    FunSpec({
        val standard = route("orders", metadata("anonymous", "standard"))
        val images = route("images", metadata("anonymous", "standard", RoutePolicy.MAX_BODY_SIZE to "${ROUTE_LIMIT}B"))
        val filter =
            RequestSizeFilter(
                policiesOf(standard, images),
                gatewayProperties { copy(maxBodySize = DataSize.ofBytes(LIMIT)) },
            )

        fun declared(
            length: Long,
            route: org.springframework.cloud.gateway.route.Route = standard,
        ): MockServerWebExchange =
            exchange(MockServerHttpRequest.post("/api/v1/orders").contentLength(length).build(), route)

        fun forwardedBy(exchange: MockServerWebExchange) =
            RecordingChain().also { filter.filter(exchange, it).block() }.forwarded.shouldNotBeNull()

        /** The bytes an upstream reads from a chunked body of [sizes]. */
        fun streamed(vararg sizes: Int): Long {
            val exchange = exchange(MockServerHttpRequest.post("/api/v1/orders").body(chunks(*sizes)), standard)
            val forwarded = forwardedBy(exchange)
            forwarded shouldNotBe exchange
            return forwarded.request.body
                .map { buffer -> buffer.readableByteCount().toLong().also { DataBufferUtils.release(buffer) } }
                .reduce(0L, Long::plus)
                .block()!!
        }

        test("runs after the rate limiter") {
            filter.order shouldBe -150
        }

        test("an unrouted request is not inspected") {
            val exchange = exchange(MockServerHttpRequest.post("/nowhere").contentLength(LIMIT + 1).build())
            forwardedBy(exchange) shouldBeSameInstanceAs exchange
        }

        test("a declared length up to the limit passes as is, one byte more answers 413 before forwarding") {
            declared(LIMIT).let { forwardedBy(it) shouldBeSameInstanceAs it }
            declared(0).let { forwardedBy(it) shouldBeSameInstanceAs it }
            val chain = RecordingChain()
            shouldThrow<GatewayProblemException> { filter.filter(declared(LIMIT + 1), chain).block() }.problem shouldBe
                GatewayProblem.PAYLOAD_TOO_LARGE
            chain.forwarded shouldBe null
        }

        test("a route's max-body-size replaces the default limit") {
            declared(ROUTE_LIMIT, images).let { forwardedBy(it) shouldBeSameInstanceAs it }
            shouldThrow<GatewayProblemException> {
                filter
                    .filter(
                        declared(ROUTE_LIMIT + 1, images),
                        RecordingChain(),
                    ).block()
            }
        }

        test("a chunked body is counted while it streams") {
            streamed(4, 6) shouldBe LIMIT
            shouldThrow<GatewayProblemException> { streamed(4, 6, 1) }.problem shouldBe GatewayProblem.PAYLOAD_TOO_LARGE
        }
    })

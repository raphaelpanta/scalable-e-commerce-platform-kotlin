package com.ecommerce.gateway.routing

import com.ecommerce.gateway.config.GatewayProperties
import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicLong

/**
 * Refuses request bodies above the route's limit (`max-body-size` metadata, default `gateway.max-body-size`, 1 MiB)
 * with 413 before anything reaches a service. A declared `Content-Length` is checked up front; a body without one
 * (chunked) is counted while it streams and the exchange fails with 413 as soon as the limit is passed.
 */
@Component
class RequestSizeFilter(
    private val policies: RoutePolicies,
    private val properties: GatewayProperties,
) : GlobalFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
    ): Mono<Void> {
        val policy = policies.of(exchange) ?: return chain.filter(exchange)
        val limit = (policy.maxBodySize ?: properties.maxBodySize).toBytes()
        val declared = exchange.request.headers.contentLength
        return when {
            declared > limit -> Mono.error(tooLarge())
            declared >= 0 -> chain.filter(exchange)
            else -> chain.filter(exchange.mutate().request(CountingRequest(exchange, limit)).build())
        }
    }

    private class CountingRequest(
        exchange: ServerWebExchange,
        private val limit: Long,
    ) : ServerHttpRequestDecorator(exchange.request) {
        override fun getBody(): Flux<DataBuffer> {
            val seen = AtomicLong()
            return super.getBody().handle { buffer, sink ->
                if (seen.addAndGet(buffer.readableByteCount().toLong()) > limit) {
                    DataBufferUtils.release(buffer)
                    sink.error(tooLarge())
                } else {
                    sink.next(buffer)
                }
            }
        }
    }

    private companion object {
        /** After rate limiting: a throttled client is told so before its body is looked at. */
        const val ORDER = -150

        fun tooLarge() = GatewayProblemException(GatewayProblem.PAYLOAD_TOO_LARGE)
    }
}

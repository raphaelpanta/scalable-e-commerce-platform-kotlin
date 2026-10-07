package com.ecommerce.gateway.web

import com.ecommerce.gateway.routing.RecordingChain
import com.ecommerce.gateway.routing.exchange
import com.ecommerce.gateway.routing.metadata
import com.ecommerce.gateway.routing.route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/** The storefront route's page headers are applied at commit, after the edge decorator hardened the response. */
class RouteHeadersFilterTest :
    FunSpec({
        val filter = RouteHeadersFilter()
        val storefront = route("storefront", metadata("anonymous", "browse"))
        val api = route("catalog-reads", metadata("anonymous", "browse"))

        /** Hardens like the edge decorator, runs the filter, commits, and returns the headers. */
        fun committed(exchange: MockServerWebExchange): HttpHeaders {
            val response = exchange.response
            response.beforeCommit {
                EdgeHeaders.harden(response.headers)
                reactor.core.publisher.Mono
                    .empty()
            }
            val chain = RecordingChain()
            filter.filter(exchange, chain).block()
            chain.forwarded shouldBeSameInstanceAs exchange
            response.headers.set(HttpHeaders.CACHE_CONTROL, "no-store")
            response.setComplete().block()
            return response.headers
        }

        test("runs before the forwarding filters") {
            filter.order shouldBe -100
        }

        test("the storefront route replaces the API CSP and adds Permissions-Policy, keeping upstream cache headers") {
            val headers = committed(exchange(MockServerHttpRequest.get("/products/1").build(), storefront))
            headers.getFirst("Content-Security-Policy") shouldBe EdgeHeaders.STOREFRONT_CSP
            headers.getFirst("Permissions-Policy") shouldBe EdgeHeaders.STOREFRONT_PERMISSIONS_POLICY
            headers.getFirst("X-Frame-Options") shouldBe "DENY"
            headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        }

        test("API routes and unrouted requests keep the API policy") {
            committed(exchange(MockServerHttpRequest.get("/api/v1/catalog/products").build(), api)).let { headers ->
                headers.getFirst("Content-Security-Policy") shouldBe EdgeHeaders.API_CSP
                headers.getFirst("Permissions-Policy").shouldBeNull()
            }
            committed(exchange()).getFirst("Content-Security-Policy") shouldBe EdgeHeaders.API_CSP
        }
    })

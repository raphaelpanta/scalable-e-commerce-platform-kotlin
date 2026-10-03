package com.ecommerce.gateway.ratelimit

import com.ecommerce.gateway.config.GatewayProperties
import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import com.ecommerce.gateway.routing.CALLER_ATTRIBUTE
import com.ecommerce.gateway.routing.Caller
import com.ecommerce.gateway.routing.RecordingChain
import com.ecommerce.gateway.routing.Tier
import com.ecommerce.gateway.routing.exchange
import com.ecommerce.gateway.routing.gatewayProperties
import com.ecommerce.gateway.routing.metadata
import com.ecommerce.gateway.routing.policiesOf
import com.ecommerce.gateway.routing.route
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.cloud.gateway.route.Route
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import java.net.InetSocketAddress

private const val AUTH_LIMIT = 2
private const val STANDARD_LIMIT = 3

class RateLimitFilterTest :
    FunSpec({
        val credentials = route("credentials", metadata("anonymous", "auth"))
        val orders = route("orders", metadata("authenticated", "standard", "operator-tier" to "operator"))
        val properties =
            gatewayProperties {
                copy(
                    rateLimit =
                        GatewayProperties.RateLimit(
                            mapOf(Tier.AUTH to AUTH_LIMIT, Tier.STANDARD to STANDARD_LIMIT, Tier.OPERATOR to 1),
                        ),
                )
            }

        fun call(
            filter: RateLimitFilter,
            route: Route?,
            address: String? = "10.0.0.1",
            caller: Caller? = null,
        ): Boolean {
            val request = MockServerHttpRequest.post("/api/v1/x")
            address?.let { request.remoteAddress(InetSocketAddress(it, 40000)) }
            val exchange = exchange(request.build(), route)
            caller?.let { exchange.attributes[CALLER_ATTRIBUTE] = it }
            val chain = RecordingChain()
            return try {
                filter.filter(exchange, chain).block()
                chain.forwarded shouldBeSameInstanceAs exchange
                true
            } catch (throttled: GatewayProblemException) {
                throttled.problem shouldBe GatewayProblem.THROTTLED
                throttled.headers[HttpHeaders.RETRY_AFTER].shouldNotBeNull().toLong() shouldBeInRange 1L..60L
                chain.forwarded shouldBe null
                false
            }
        }

        fun allowedOf(
            attempts: Int,
            request: (RateLimitFilter) -> Boolean,
        ): Int {
            val filter = RateLimitFilter(policiesOf(credentials, orders), properties)
            return (1..attempts).count { request(filter) }
        }

        test("runs after the access filter and before the size filter") {
            RateLimitFilter(policiesOf(), properties).order shouldBe -200
        }

        test("unrouted requests are never charged") {
            allowedOf(10) { call(it, route = null) } shouldBe 10
        }

        test("the auth tier is charged per source address, also for signed-in callers") {
            val caller = Caller("account-1", setOf("shopper"))
            allowedOf(AUTH_LIMIT + 2) { call(it, credentials, caller = caller) } shouldBe AUTH_LIMIT
            val filter = RateLimitFilter(policiesOf(credentials), properties)
            repeat(AUTH_LIMIT) { call(filter, credentials, "10.0.0.1") }
            call(filter, credentials, "10.0.0.2") shouldBe true
            call(filter, credentials, "10.0.0.1", Caller("account-2", emptySet())) shouldBe false
        }

        test("other tiers are charged per account for signed-in callers, per address otherwise") {
            val filter = RateLimitFilter(policiesOf(orders), properties)
            val first = Caller("account-1", setOf("shopper"))
            repeat(STANDARD_LIMIT) { call(filter, orders, "10.0.0.1", first) shouldBe true }
            call(filter, orders, "10.0.0.2", first) shouldBe false
            call(filter, orders, "10.0.0.1", Caller("account-2", setOf("shopper"))) shouldBe true
            call(filter, orders, "10.0.0.1") shouldBe true
        }

        test("requests without a source address share one budget") {
            allowedOf(STANDARD_LIMIT + 1) { call(it, orders, address = null) } shouldBe STANDARD_LIMIT
        }

        test("tiers have separate budgets, and operators are charged to the operator tier") {
            val filter = RateLimitFilter(policiesOf(credentials, orders), properties)
            repeat(AUTH_LIMIT) { call(filter, credentials) }
            call(filter, credentials) shouldBe false
            call(filter, orders) shouldBe true
            val operator = Caller("operator-1", setOf("operator"))
            call(filter, orders, caller = operator) shouldBe true
            call(filter, orders, caller = operator) shouldBe false
            call(filter, orders, caller = Caller("operator-1", setOf("shopper"))) shouldBe true
        }

        test("limits default to the tier's documented budget") {
            properties.rateLimit.limitOf(Tier.BROWSE) shouldBe Tier.BROWSE.defaultRequestsPerMinute
            properties.rateLimit.limitOf(Tier.AUTH) shouldBe AUTH_LIMIT
            GatewayProperties.RateLimit().requestsPerMinute shouldBe
                Tier.entries.associateWith { it.defaultRequestsPerMinute }
        }
    })

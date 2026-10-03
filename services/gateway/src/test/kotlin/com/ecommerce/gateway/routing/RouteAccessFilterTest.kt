package com.ecommerce.gateway.routing

import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import com.ecommerce.gateway.web.EdgeHeaders
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.server.MockServerWebExchange
import java.security.Principal

private const val ACCOUNT = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"

class RouteAccessFilterTest :
    FunSpec({
        val anonymous = route("cart", metadata("anonymous", "standard"))
        val authenticatedRoute = route("profile", metadata("authenticated", "standard"))
        val shopperRoute = route("orders", metadata("shopper", "standard"))
        val operatorRoute = route("order-status", metadata("operator", "operator"))
        val filter = RouteAccessFilter(policiesOf(anonymous, authenticatedRoute, shopperRoute, operatorRoute))

        fun admitted(exchange: MockServerWebExchange): MockServerWebExchange =
            exchange.also { RecordingChain().let { chain -> filter.filter(it, chain).block() } }

        fun forwardedBy(exchange: MockServerWebExchange) =
            RecordingChain().also { filter.filter(exchange, it).block() }.forwarded.shouldNotBeNull()

        fun refusal(exchange: MockServerWebExchange): GatewayProblem =
            shouldThrow<GatewayProblemException> { filter.filter(exchange, RecordingChain()).block() }.problem

        /** Commits the response so that the before-commit actions run, and returns its headers. */
        fun committedHeaders(exchange: MockServerWebExchange): HttpHeaders {
            exchange.response.setComplete().block()
            return exchange.response.headers
        }

        test("runs before the rate limiter, which needs the caller") {
            filter.order shouldBe -300
        }

        test("an unrouted request passes untouched") {
            val exchange = exchange()
            forwardedBy(exchange) shouldBeSameInstanceAs exchange
        }

        test("an anonymous call on an anonymous route passes unchanged and stays cacheable") {
            val exchange = exchange(route = anonymous)
            exchange.response.headers.set(HttpHeaders.EXPIRES, "0")
            forwardedBy(exchange) shouldBeSameInstanceAs exchange
            exchange.getAttribute<Caller>(CALLER_ATTRIBUTE).shouldBeNull()
            committedHeaders(exchange).getFirst(HttpHeaders.CACHE_CONTROL).shouldBeNull()
            exchange.response.headers.getFirst(HttpHeaders.EXPIRES) shouldBe "0"
        }

        test("protected routes refuse missing tokens with 401 and missing roles with 403") {
            refusal(exchange(route = authenticatedRoute)) shouldBe GatewayProblem.UNAUTHORIZED
            refusal(exchange(route = shopperRoute)) shouldBe GatewayProblem.UNAUTHORIZED
            refusal(exchange(route = operatorRoute, principal = authenticated(ACCOUNT, listOf("shopper")))) shouldBe
                GatewayProblem.FORBIDDEN
            refusal(exchange(route = shopperRoute, principal = authenticated(ACCOUNT, listOf("operator")))) shouldBe
                GatewayProblem.FORBIDDEN
        }

        test("a principal that is not a validated token, or a token without subject, counts as anonymous") {
            val other = Principal { "basic-user" }
            refusal(exchange(route = shopperRoute, principal = other)) shouldBe GatewayProblem.UNAUTHORIZED
            refusal(exchange(route = shopperRoute, principal = authenticated(null, listOf("shopper")))) shouldBe
                GatewayProblem.UNAUTHORIZED
        }

        test("an admitted caller is forwarded with the gateway's identity headers and marked no-store") {
            val exchange =
                exchange(route = operatorRoute, principal = authenticated(ACCOUNT, listOf("shopper", "operator")))
            exchange.response.headers.set(HttpHeaders.EXPIRES, "0")
            val forwarded = forwardedBy(exchange)

            forwarded.request.headers.getFirst(EdgeHeaders.ACCOUNT_ID) shouldBe ACCOUNT
            forwarded.request.headers.getFirst(EdgeHeaders.ROLES) shouldBe "operator,shopper"
            exchange.getAttribute<Caller>(CALLER_ATTRIBUTE) shouldBe Caller(ACCOUNT, setOf("shopper", "operator"))
            exchange.getAttribute<Caller>(CALLER_ATTRIBUTE)?.accountId shouldBe ACCOUNT
            committedHeaders(exchange).getFirst(HttpHeaders.CACHE_CONTROL) shouldBe EdgeHeaders.NO_STORE
            exchange.response.headers
                .getFirst(HttpHeaders.EXPIRES)
                .shouldBeNull()
        }

        test("a token without roles is authenticated with no role") {
            val forwarded = forwardedBy(exchange(route = authenticatedRoute, principal = authenticated(ACCOUNT)))
            forwarded.request.headers.getFirst(EdgeHeaders.ROLES) shouldBe ""
        }

        test("a signed-in caller on an anonymous route is identified and its answer is not cached") {
            val exchange = admitted(exchange(route = anonymous, principal = authenticated(ACCOUNT, listOf("shopper"))))
            exchange.getAttribute<Caller>(CALLER_ATTRIBUTE)?.accountId shouldBe ACCOUNT
            committedHeaders(exchange).getFirst(HttpHeaders.CACHE_CONTROL) shouldBe EdgeHeaders.NO_STORE
        }

        test("a protected route marks even its refusals no-store") {
            val exchange = exchange(route = shopperRoute)
            refusal(exchange)
            committedHeaders(exchange).getFirst(HttpHeaders.CACHE_CONTROL) shouldBe EdgeHeaders.NO_STORE
        }

        test("a route missing from the start-up table is parsed from its own metadata") {
            val late = route("late", metadata("shopper", "standard"))
            refusal(exchange(route = late)) shouldBe GatewayProblem.UNAUTHORIZED
        }
    })

package com.ecommerce.gateway.browser

import com.ecommerce.gateway.routing.RecordingChain
import com.ecommerce.gateway.routing.exchange
import com.ecommerce.gateway.routing.metadata
import com.ecommerce.gateway.routing.route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

private const val MAX_AGE_DAYS = 30L
private const val TOKEN = "tok-cart-1"
private const val SET_COOKIE = HttpHeaders.SET_COOKIE

/** Unit layer of the cart cookie filter: injection, mirroring, deletion after a merge, browser mode only. */
class CartCookieFilterTest :
    FunSpec({
        val sealer =
            AesGcmSessionSealer(
                mapOf("k1" to SecretKeySpec(ByteArray(AesGcmSessionSealer.KEY_BYTES) { 5 }, "AES")),
                "k1",
            )
        val filter = CartCookieFilter(sealer, Duration.ofDays(MAX_AGE_DAYS))
        val cart = route("cart", metadata("anonymous", "standard"))
        val lines = route("cart-line-addition", metadata("anonymous", "standard"))
        val merge = route("cart-merge", metadata("authenticated", "standard"))
        val storefront = route("storefront", metadata("anonymous", "browse"))

        @Suppress("LongParameterList") // one optional knob per request part the cart rules read
        fun request(
            method: String = "GET",
            path: String = "/api/v1/cart",
            cookie: String? = sealer.sealToken(TOKEN),
            cookieName: String = "cart",
            scheme: String = "http",
            browserMode: Boolean = true,
            configure: MockServerHttpRequest.BaseBuilder<*>.() -> Unit = {},
        ): MockServerHttpRequest {
            val builder =
                MockServerHttpRequest.method(
                    HttpMethod.valueOf(method),
                    URI("$scheme://shop.example:8080$path"),
                )
            cookie?.let { builder.cookie(HttpCookie(cookieName, it)) }
            if (browserMode) builder.header(BROWSER_SESSION_HEADER, BROWSER_SESSION_COOKIE_MODE)
            builder.configure()
            return builder.build()
        }

        fun forwarded(exchange: MockServerWebExchange): ServerWebExchange =
            RecordingChain()
                .also {
                    filter
                        .filter(
                            exchange,
                            it,
                        ).block()
                }.forwarded
                .shouldNotBeNull()

        fun committed(
            exchange: MockServerWebExchange,
            status: HttpStatus = HttpStatus.OK,
            configure: HttpHeaders.() -> Unit = {},
        ): HttpHeaders {
            exchange.response.statusCode = status
            exchange.response.headers.configure()
            exchange.response.setComplete().block()
            return exchange.response.headers
        }

        test("runs between the session filter and the route access filter") {
            filter.order shouldBe -350
        }

        test("without browser mode, on ignored routes or unrouted, nothing changes and X-Cart-Token stays visible") {
            exchange(request(browserMode = false), cart).let { exchange ->
                forwarded(exchange) shouldBeSameInstanceAs exchange
                committed(exchange) { set(CART_TOKEN_HEADER, TOKEN) }.getFirst(CART_TOKEN_HEADER) shouldBe TOKEN
            }
            exchange(request(path = "/"), storefront).let { forwarded(it) shouldBeSameInstanceAs it }
            exchange(request()).let { forwarded(it) shouldBeSameInstanceAs it }
        }

        test("the cart cookie is injected as X-Cart-Token when the client sent none; an explicit header wins") {
            forwarded(exchange(request(), cart)).request.headers.getFirst(CART_TOKEN_HEADER) shouldBe TOKEN
            val explicit = exchange(request { header(CART_TOKEN_HEADER, "explicit") }, cart)
            forwarded(explicit) shouldBeSameInstanceAs explicit
            forwarded(
                exchange(request(cookieName = "__Host-cart", scheme = "https"), cart),
            ).request.headers.getFirst(CART_TOKEN_HEADER) shouldBe
                TOKEN
            exchange(request(cookie = null), cart).let { forwarded(it) shouldBeSameInstanceAs it }
        }

        test("an unsealable cookie, or both names at once, is ignored and deleted") {
            val broken = exchange(request(cookie = "k1.nope"), cart)
            forwarded(broken) shouldBeSameInstanceAs broken
            committed(broken)[SET_COOKIE] shouldBe listOf("cart=; Max-Age=0; HttpOnly; SameSite=Lax; Path=/")

            val both = exchange(request { cookie(HttpCookie("__Host-cart", sealer.sealToken("other"))) }, cart)
            forwarded(both) shouldBeSameInstanceAs both
            committed(both)[SET_COOKIE] shouldBe BrowserCookies.deleteBoth(CookieKind.CART)
        }

        test("an upstream X-Cart-Token is sealed into the cart cookie for 30 days and removed from the response") {
            val exchange = exchange(request(method = "POST", path = "/api/v1/cart/lines", cookie = null), lines)
            forwarded(exchange) shouldBeSameInstanceAs exchange
            val headers = committed(exchange, HttpStatus.CREATED) { set(CART_TOKEN_HEADER, "tok-new") }
            headers.getFirst(CART_TOKEN_HEADER).shouldBeNull()
            val cookies = headers[SET_COOKIE].shouldNotBeNull()
            cookies.map { it.substringBefore('=') } shouldContainExactly listOf("cart", "__Host-cart")
            cookies.first() shouldStartWith "cart=k1."
            cookies.first() shouldEndWith "; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000"
            sealer.unsealToken(cookies.first().removePrefix("cart=").substringBefore(';')) shouldBe "tok-new"
            cookies.last() shouldBe "__Host-cart=; Max-Age=0; HttpOnly; Secure; SameSite=Lax; Path=/"
        }

        test("over HTTPS the mirrored cookie is __Host-cart with Secure") {
            val exchange =
                exchange(request(method = "POST", path = "/api/v1/cart/lines", cookie = null, scheme = "https"), lines)
            forwarded(exchange)
            val cookies =
                committed(exchange, HttpStatus.CREATED) {
                    set(CART_TOKEN_HEADER, "tok-new")
                }[SET_COOKIE].shouldNotBeNull()
            cookies.first() shouldStartWith "__Host-cart=k1."
            cookies.first() shouldEndWith "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=2592000"
            cookies.last() shouldBe "cart=; Max-Age=0; HttpOnly; SameSite=Lax; Path=/"
        }

        test("a successful merge deletes the cart cookie; a 409 keeps it") {
            val merged = exchange(request(method = "POST", path = "/api/v1/cart/merge"), merge)
            forwarded(merged).request.headers.getFirst(CART_TOKEN_HEADER) shouldBe TOKEN
            committed(merged)[SET_COOKIE] shouldBe listOf("cart=; Max-Age=0; HttpOnly; SameSite=Lax; Path=/")

            val inProgress = exchange(request(method = "POST", path = "/api/v1/cart/merge"), merge)
            forwarded(inProgress)
            committed(inProgress, HttpStatus.CONFLICT)[SET_COOKIE].shouldBeNull()
        }

        test("a token too large for the cookie budget is passed through as the header instead") {
            val exchange = exchange(request(method = "POST", path = "/api/v1/cart/lines", cookie = null), lines)
            forwarded(exchange)
            val huge = "t".repeat(BrowserCookies.MAX_COOKIE_BYTES)
            val headers = committed(exchange, HttpStatus.CREATED) { set(CART_TOKEN_HEADER, huge) }
            headers.getFirst(CART_TOKEN_HEADER) shouldBe huge
            headers[SET_COOKIE].shouldBeNull()
        }
    })

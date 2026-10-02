package com.ecommerce.platform.http

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.testing.InternalToken
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.PrematureCloseException
import reactor.util.context.Context
import java.net.ConnectException
import java.net.UnknownHostException

class WebClientDefaultsSpec :
    FunSpec({
        val server = WireMockServer(options().dynamicPort())
        beforeSpec { server.start() }
        afterSpec { server.stop() }
        beforeTest { server.resetAll() }

        fun client(token: String? = InternalToken.TEST): WebClient =
            WebClientDefaults.internalClient(WebClient.builder(), server.baseUrl(), token)

        test("internal token and correlation id are sent") {
            server.stubFor(get(urlEqualTo("/internal/ping")).willReturn(aResponse().withStatus(200).withBody("pong")))
            val body =
                withContext(ReactorContext(Context.of(CorrelationIds.CONTEXT_KEY, "corr-1"))) {
                    client()
                        .get()
                        .uri("/internal/ping")
                        .retrieve()
                        .awaitBody<String>()
                }
            body shouldBe "pong"
            server.verify(
                getRequestedFor(urlEqualTo("/internal/ping"))
                    .withHeader(InternalToken.HEADER, equalTo(InternalToken.TEST))
                    .withHeader(CorrelationIds.HEADER, equalTo("corr-1")),
            )
        }

        test("without a correlation id or token nothing is invented") {
            server.stubFor(get(urlEqualTo("/x")).willReturn(aResponse().withStatus(200).withBody("ok")))
            client(token = " ")
                .get()
                .uri("/x")
                .retrieve()
                .awaitBody<String>() shouldBe "ok"
            val request = server.allServeEvents.single().request
            request.containsHeader(InternalToken.HEADER) shouldBe false
            request.containsHeader(CorrelationIds.HEADER) shouldBe false
        }

        test("problem answers are decoded and never retried") {
            server.stubFor(
                get(urlEqualTo("/internal/products/1")).willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(
                            """{"type":"https://ecommerce.example/problems/not-found","title":"Not found",""" +
                                """"status":404,"detail":"Product not found.","correlationId":"c"}""",
                        ),
                ),
            )
            val error =
                client()
                    .get()
                    .uri("/internal/products/1")
                    .awaitBodyOrProblem<String>()
                    .leftOrNull()
            error?.status shouldBe 404
            error?.problem?.problemType shouldBe ProblemType.NOT_FOUND
            error?.problem?.detail shouldBe "Product not found."
            error?.isType("not-found") shouldBe true
            server.verify(1, getRequestedFor(urlEqualTo("/internal/products/1")))
        }

        test("a 5xx without a problem body is still an error, not retried") {
            server.stubFor(get(urlEqualTo("/boom")).willReturn(aResponse().withStatus(503).withBody("<html>")))
            val error =
                client()
                    .get()
                    .uri("/boom")
                    .awaitBodyOrProblem<String>()
                    .leftOrNull()
            error?.status shouldBe 503
            error?.problem shouldBe null
            server.verify(1, getRequestedFor(urlEqualTo("/boom")))
            server.stubFor(get(urlEqualTo("/empty")).willReturn(aResponse().withStatus(204)))
            client()
                .get()
                .uri("/empty")
                .awaitOptionalBodyOrProblem<String>()
                .getOrNull() shouldBe null
            server.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200).withBody("fine")))
            client()
                .get()
                .uri("/ok")
                .awaitBodyOrProblem<String>()
                .getOrNull() shouldBe "fine"
        }

        test("a dropped connection is retried twice") {
            server.stubFor(get(urlEqualTo("/flaky")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))
            shouldThrow<WebClientRequestException> {
                client()
                    .get()
                    .uri("/flaky")
                    .retrieve()
                    .awaitBody<String>()
            }
            server.verify(1 + WebClientDefaults.MAX_RETRIES.toInt(), getRequestedFor(urlEqualTo("/flaky")))
        }

        test("connection errors are recognised through their causes") {
            WebClientDefaults.isConnectionError(ConnectException("refused")) shouldBe true
            WebClientDefaults.isConnectionError(RuntimeException(UnknownHostException("catalog"))) shouldBe true
            WebClientDefaults.isConnectionError(IllegalStateException(PrematureCloseException.TEST_EXCEPTION)) shouldBe
                true
            WebClientDefaults.isConnectionError(IllegalStateException("no")) shouldBe false
        }
    })

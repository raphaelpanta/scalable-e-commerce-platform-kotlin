package com.ecommerce.gateway

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val OK = 200

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "echo", pactVersion = PactSpecVersion.V4)
class EchoConsumerPactTest {
    @Pact(consumer = "gateway")
    fun echo(builder: PactDslWithProvider): V4Pact =
        builder
            .uponReceiving("an echo request")
            .path("/echo")
            .method("GET")
            .willRespondWith()
            .status(OK)
            .body(newJsonBody { it.stringValue("message", "hello") }.build())
            .toPact(V4Pact::class.java)

    @Test
    fun gatewayReadsTheEcho(mockServer: MockServer) {
        val response =
            HttpClient.newHttpClient().use { http ->
                http.send(
                    HttpRequest.newBuilder(URI.create("${mockServer.getUrl()}/echo")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }

        response.statusCode() shouldBe OK
    }
}

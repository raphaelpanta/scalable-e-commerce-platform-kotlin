package com.ecommerce.payment.infrastructure

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
private const val ACCEPT = "Accept"
private const val JSON = "application/json"

/**
 * Consumer side of the health edge: the platform's probe expects `GET /actuator/health` to answer 200 with
 * `{"status":"UP"}`. Runs in `contractTest` and writes the pact to the repository root `build/pacts`
 * (`pact.rootDir`); [HealthProviderVerificationTest] verifies it in `contractVerify`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "payment", pactVersion = PactSpecVersion.V4)
class HealthConsumerPactTest {
    @Pact(consumer = "platform-probe")
    fun healthyService(builder: PactDslWithProvider): V4Pact =
        builder
            .given("the payment service is running")
            .uponReceiving("a health check")
            .path("/actuator/health")
            .method("GET")
            .headers(ACCEPT, JSON)
            .willRespondWith()
            .status(OK)
            .body(newJsonBody { it.stringValue("status", "UP") }.build())
            .toPact(V4Pact::class.java)

    @Test
    fun probeReadsAnUpStatus(mockServer: MockServer) {
        val response =
            HttpClient.newHttpClient().use { http ->
                http.send(
                    HttpRequest
                        .newBuilder(URI.create("${mockServer.getUrl()}/actuator/health"))
                        .header(ACCEPT, JSON)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }

        response.statusCode() shouldBe OK
        response.body() shouldBe """{"status":"UP"}"""
    }
}

package com.ecommerce.identity.infrastructure

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.cucumber.spring.CucumberContextConfiguration
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

/**
 * Step definitions for `service-status.feature`. Cucumber's Spring integration starts the application once
 * for the whole run (with a throwaway PostgreSQL) and the steps ask it over HTTP.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class)
class ServiceStatusSteps(
    @LocalServerPort private val port: Int,
    private val context: ConfigurableApplicationContext,
) {
    private lateinit var answer: HttpResponse<String>

    @Given("the identity service is running")
    fun theServiceIsRunning() {
        context.isRunning shouldBe true
    }

    @When("an operator asks whether the service is healthy")
    fun anOperatorAsksWhetherTheServiceIsHealthy() {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        answer = http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Then("the service reports that it is healthy")
    fun theServiceReportsThatItIsHealthy() {
        answer.statusCode() shouldBe HTTP_OK
        answer.body() shouldBe """{"status":"UP"}"""
    }

    private companion object {
        const val HTTP_OK = 200
    }
}

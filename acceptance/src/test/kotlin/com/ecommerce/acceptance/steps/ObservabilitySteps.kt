package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.ApiClient
import com.ecommerce.acceptance.support.Budgets
import com.ecommerce.acceptance.support.Headers
import com.ecommerce.acceptance.support.PLATFORM_SERVICES
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.eventually
import com.ecommerce.acceptance.support.shouldBeProblem
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.ParameterType
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The single entry point, correlation ids, central logs and metrics (user story 8, gateway-routes.md). Logs and
 * metrics are read through Grafana (see [com.ecommerce.acceptance.support.Telemetry]).
 */
class ObservabilitySteps(
    private val world: ScenarioWorld,
) {
    private var sentCorrelationId: String = ""

    /** Service names written `a, b and c` in the feature files. */
    @ParameterType("[a-z]+(?:, [a-z]+)*(?: and [a-z]+)?")
    fun servicesList(names: String): List<String> = names.split(", ", " and ").map(String::trim)

    @When("a client requests a capability that does not exist")
    fun requestsAnUnknownCapability() {
        world.api.get("/api/v1/unknown-capability/${ApiClient.newKey()}")
    }

    @When("a client requests a service's health endpoint through the public entry point")
    fun requestsAHealthEndpoint() {
        world.api.get("/actuator/health")
    }

    @Then("the client receives a clear not-found answer")
    fun aClearNotFound() {
        val answer = world.api.last
        answer.shouldBeProblem(Status.NOT_FOUND, "not-found")
        answer.header(Headers.CORRELATION_ID) shouldBe world.correlationId
    }

    @When("a client sends a request with a malformed correlation identifier")
    fun sendsAMalformedCorrelationId() {
        sentCorrelationId = "not*a valid*id"
        val answer = world.api.get(Paths.CATEGORIES, headers = ApiClient.withCorrelationId(sentCorrelationId))
        answer shouldHaveStatus Status.OK
    }

    @Then("the answer carries a different, well-formed correlation identifier")
    fun aReplacementCorrelationId() {
        val replacement = world.api.last.header(Headers.CORRELATION_ID)
        replacement.shouldNotBeNull()
        replacement shouldNotBe sentCorrelationId
        replacement shouldMatch WELL_FORMED
    }

    @Then("the central log holds entries with the checkout's correlation identifier from at least {int} services")
    fun logsFromSeveralServices(count: Int) {
        val correlationId = checkNotNull(world.tracedCorrelationId) { "No checkout was traced" }
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            val services = world.telemetry.servicesLogging(correlationId)
            withClue("services logging correlation id $correlationId: $services") {
                services.size shouldBeGreaterThanOrEqual count
            }
        }
    }

    /**
     * The services that must have logged the checkout's correlation id: order handles the request, payment the
     * synchronous charge (its access line) and notification the `OrderPaid` event (its consumer's line, the
     * correlation id coming through the outbox and `EventListenerSupport`). Names as in [PLATFORM_SERVICES].
     */
    @Then("the central log holds entries with the checkout's correlation identifier from the {servicesList} services")
    fun logsFromNamedServices(required: List<String>) {
        val correlationId = checkNotNull(world.tracedCorrelationId) { "No checkout was traced" }
        required.forEach { service -> check(service in PLATFORM_SERVICES) { "Unknown service $service" } }
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            val services = world.telemetry.servicesLogging(correlationId)
            withClue("services logging correlation id $correlationId: $services") {
                services shouldContainAll required
            }
        }
    }

    @Then("the central log records the refused attempt with its correlation identifier")
    fun refusedAttemptIsLogged() {
        val correlationId = checkNotNull(world.tracedCorrelationId) { "No attempt was traced" }
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            world.telemetry.servicesLogging(correlationId).shouldNotBeEmpty()
        }
    }

    @Given("traffic has reached every service")
    fun trafficHasReachedEveryService() {
        val shopper = world.accounts.signedInShopper()
        world.api.get(Paths.PRODUCTS) shouldHaveStatus Status.OK
        world.api.get(Paths.CART) shouldHaveStatus Status.OK
        world.api.get(Paths.ORDERS, shopper.bearer) shouldHaveStatus Status.OK
        world.api.get(Paths.NOTIFICATIONS, shopper.bearer) shouldHaveStatus Status.OK
        world.api.get(Paths.query(Paths.PAYMENT_ATTEMPTS, "orderId" to ApiClient.newKey()), shopper.bearer)
    }

    @Then("every service of the platform is up and reporting request, error and latency metrics")
    fun everyServiceIsUpWithMetrics() {
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            val up = world.telemetry.prometheus("""min by (service) (up{job="services"}) == 1""")
            withClue("services up") { servicesIn(up) shouldContainAll PLATFORM_SERVICES }
            METRICS.forEach { metric ->
                val reporting = world.telemetry.prometheus("count by (service) ($metric)")
                withClue("services exposing $metric") {
                    servicesIn(reporting) shouldContainAll PLATFORM_SERVICES
                }
            }
        }
    }

    private fun servicesIn(vector: List<JsonNode>): List<String> =
        vector.mapNotNull { sample ->
            sample.path("metric").string("service")
        }

    private companion object {
        const val TELEMETRY_POLL_SECONDS = 5L
        val WELL_FORMED = Regex("[A-Za-z0-9-]{16,64}")

        /** Request count and errors (label `outcome`/`status`) and the latency histogram of Micrometer. */
        val METRICS =
            listOf(
                "http_server_requests_seconds_count",
                "http_server_requests_seconds_bucket",
            )
    }
}

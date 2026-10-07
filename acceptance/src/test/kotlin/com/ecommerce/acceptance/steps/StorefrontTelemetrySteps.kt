package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.Accounts
import com.ecommerce.acceptance.support.Budgets
import com.ecommerce.acceptance.support.Disclosure
import com.ecommerce.acceptance.support.PLATFORM_SERVICES
import com.ecommerce.acceptance.support.PageView
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.eventually
import com.ecommerce.acceptance.support.shouldHaveStatus
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Browser telemetry of the storefront (feature 005, FR-031, FR-032, SC-011). The suite has no browser: a step issues
 * the requests the storefront's page view makes and posts the OTLP/HTTP JSON its telemetry module would export (see
 * [PageView]); the central log (Loki) and the traces (Tempo) are then read through Grafana like in
 * [ObservabilitySteps].
 */
class StorefrontTelemetrySteps(
    private val world: ScenarioWorld,
) {
    private var pageView: PageView = PageView()
    private var unfilteredPageView: PageView? = null
    private var route: String = Paths.CATEGORIES
    private var searchTerm: String = ""

    @When("a shopper opens the storefront and it loads the catalogue")
    fun opensTheStorefront() {
        visit(Paths.CATEGORIES)
    }

    @When("the shopper searches the storefront for {string}")
    fun searchesTheStorefront(term: String) {
        searchTerm = term
        visit(Paths.query(Paths.PRODUCTS, "q" to term))
    }

    @When("the storefront reports the page view and a failed action")
    fun reportsThePageView() {
        report(pageView, null)
    }

    @When("an unfiltered storefront reports the page with the shopper's email, address and search term")
    fun reportsUnfiltered() {
        val unfiltered = PageView().also { unfilteredPageView = it }
        report(unfiltered, Disclosure(world.theShopper().email, Accounts.ADDRESS_LINE, searchTerm))
    }

    @Then("the central log holds entries with the page view's correlation identifier from the {servicesList} services")
    fun logsFromTheServices(required: List<String>) {
        required.forEach { service -> check(service in PLATFORM_SERVICES + STOREFRONT) { "Unknown service $service" } }
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            val services = loggingServices(pageView)
            withClue("services logging correlation id ${pageView.correlationId}: $services") {
                services shouldContainAll required
            }
        }
    }

    @Then("the page view is one trace holding the spans of the {servicesList} services")
    fun oneTraceOfTheServices(required: List<String>) {
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            val services = world.telemetry.traceServices(pageView.traceId)
            withClue("services in trace ${pageView.traceId}: $services") { services shouldContainAll required }
        }
    }

    @Then("the storefront telemetry has reached the central log and the traces")
    fun theTelemetryHasArrived() {
        val reported = listOfNotNull(pageView, unfilteredPageView)
        eventually(within = Budgets.telemetry, every = Duration.ofSeconds(TELEMETRY_POLL_SECONDS)) {
            reported.forEach { view ->
                withClue("storefront log entries of correlation id ${view.correlationId}") {
                    world.telemetry.storefrontLogs(view.correlationId).shouldNotBeEmpty()
                }
                withClue("services in trace ${view.traceId}") {
                    world.telemetry.traceServices(view.traceId) shouldContain STOREFRONT
                }
            }
        }
    }

    @Then("no storefront telemetry contains the shopper's email, address or search term")
    fun nothingPersonalWasStored() {
        val disclosure = Disclosure(world.theShopper().email, Accounts.ADDRESS_LINE, searchTerm)
        val logs = world.telemetry.allStorefrontLogs().map { it.text() }
        val spans = world.telemetry.allStorefrontSpans()
        withClue("the storefront's log entries and spans to scan") {
            logs.shouldNotBeEmpty()
            spans.shouldNotBeEmpty()
        }
        disclosure.needles().forEach { needle ->
            withClue("storefront log entries containing a personal text of the scenario") {
                logs.count { it.contains(needle, ignoreCase = true) } shouldBe 0
            }
            withClue("storefront spans containing a personal text of the scenario") {
                spans.count { it.contains(needle, ignoreCase = true) } shouldBe 0
            }
        }
    }

    /** The requests of one page view: the storefront document, then a platform call the way its fetch sends it. */
    private fun visit(path: String) {
        pageView = PageView()
        route = path.substringBefore('?')
        world.api.get("/") shouldHaveStatus Status.OK
        world.api.get(path, headers = pageView.requestHeaders()) shouldHaveStatus Status.OK
    }

    private fun report(
        view: PageView,
        disclosure: Disclosure?,
    ) {
        val headers = view.requestHeaders()
        world.api.post(Paths.TELEMETRY_TRACES, view.traces(route, disclosure), headers = headers) shouldHaveStatus
            Status.OK
        world.api.post(Paths.TELEMETRY_LOGS, view.logs(route, disclosure), headers = headers) shouldHaveStatus Status.OK
    }

    private fun loggingServices(view: PageView): Set<String> {
        val storefront =
            if (world.telemetry
                    .storefrontLogs(
                        view.correlationId,
                    ).isEmpty()
            ) {
                emptySet()
            } else {
                setOf(STOREFRONT)
            }
        return world.telemetry.servicesLogging(view.correlationId) + storefront
    }

    private companion object {
        const val STOREFRONT = "storefront"
        const val TELEMETRY_POLL_SECONDS = 5L
    }
}

package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.ApiClient
import com.ecommerce.acceptance.support.Capabilities
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.items
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

/**
 * The authorisation sweep (SC-010, T114): capabilities named in the feature files are mapped to contract
 * operations by [Capabilities] and attempted with a shopper's session or without any.
 */
class AuthorisationSteps(
    private val world: ScenarioWorld,
) {
    private var forgedBearer: String = ""

    @When("the shopper attempts the operator capability {string}")
    fun theShopperAttempts(capability: String) {
        attempt(capability, world.theShopper().bearer)
    }

    @When("an anonymous caller attempts the capability {string}")
    fun anAnonymousCallerAttempts(capability: String) {
        attempt(capability, null)
    }

    @When("the shopper tries to adjust the stock of {string} by {int} units")
    fun theShopperTriesToAdjustStock(
        alias: String,
        delta: Int,
    ) {
        world.api.post(
            Paths.stockAdjustments(world.product(alias).id),
            mapOf("delta" to delta, "reason" to "A shopper must not be able to do this"),
            world.theShopper().bearer,
        )
    }

    @When("the shopper tries to move the order to {word}")
    fun theShopperTriesToMoveTheOrder(orderStatus: String) {
        world.orders.transition(world.theShopper().bearer, world.orderId(), orderStatus)
    }

    @When("an operator lists the orders in status {string}")
    fun anOperatorListsTheOrders(orderStatus: String) {
        world.orders.listWithStatus(world.operator(), orderStatus) shouldHaveStatus Status.OK
    }

    @Then("the operator's list holds the orders of both shoppers")
    fun theOperatorsListHoldsBoth() {
        val listed = world.api.last.body.items().map { it.string("id") }
        listed shouldContain world.orderId()
        listed shouldContain checkNotNull(world.otherOrderId) { "No other shopper's order" }
    }

    @Then("the operator's list holds neither of their orders")
    fun theOperatorsListHoldsNeither() {
        val listed = world.api.last.body.items().map { it.string("id") }
        listed shouldNotContain world.orderId()
        listed shouldNotContain checkNotNull(world.otherOrderId) { "No other shopper's order" }
    }

    @Given("a caller presenting a forged session")
    fun aForgedSession() {
        forgedBearer = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJmb3JnZWQiLCJyb2xlcyI6WyJvcGVyYXRvciJdfQ.${ApiClient.newKey()}"
    }

    @When("the caller browses the catalogue")
    fun browsesWithTheForgedSession() {
        world.api.get(Paths.PRODUCTS, forgedBearer)
    }

    /** Sends the operation with its own correlation id, so that the attempt can be found in the central log. */
    private fun attempt(
        capability: String,
        bearer: String?,
    ) {
        val operation = Capabilities(productId = world.latestProduct()?.id ?: ApiClient.newKey()).operation(capability)
        val correlationId = ApiClient.newKey()
        world.tracedCorrelationId = correlationId
        val headers = operation.headers + ApiClient.withCorrelationId(correlationId)
        world.api.send(operation.method, operation.path, operation.body, bearer, headers)
    }
}

package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.ApiClient
import com.ecommerce.acceptance.support.Capabilities
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Given
import io.cucumber.java.en.When

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

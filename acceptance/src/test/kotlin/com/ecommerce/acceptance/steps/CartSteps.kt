package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.CartHolder
import com.ecommerce.acceptance.support.Carts
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.list
import com.ecommerce.acceptance.support.minor
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import tools.jackson.databind.JsonNode

/** The shopping cart, anonymous and account-bound (user story 2, cart.yaml). */
class CartSteps(
    private val world: ScenarioWorld,
) {
    @When("an anonymous shopper adds {int} {string} to the cart")
    fun anAnonymousShopperAdds(
        quantity: Int,
        alias: String,
    ) {
        world.carts.add(world.anonymousCart, world.product(alias).id, quantity) shouldHaveStatus Status.CREATED
    }

    @When("an anonymous shopper tries to add {int} {string} to the cart")
    fun anAnonymousShopperTriesToAdd(
        quantity: Int,
        alias: String,
    ) {
        world.carts.add(world.anonymousCart, world.product(alias).id, quantity)
    }

    @Then("a shopper can add {int} {string} to a cart")
    fun aShopperCanAdd(
        quantity: Int,
        alias: String,
    ) {
        world.carts.add(CartHolder(), world.product(alias).id, quantity) shouldHaveStatus Status.CREATED
    }

    @Then("{string} can no longer be added to a cart")
    fun canNoLongerBeAdded(alias: String) {
        world.carts.add(CartHolder(), world.product(alias).id, 1) shouldHaveStatus Status.UNPROCESSABLE
    }

    @Then("the cart has {int} line(s)")
    fun theCartHasLines(count: Int) {
        cart().list("lines") shouldHaveSize count
    }

    @Then("the cart has no lines")
    fun theCartHasNoLines() {
        cart().list("lines").shouldBeEmpty()
    }

    @Then("the line for {string} has quantity {int} at a unit price of {money}")
    fun theLineHas(
        alias: String,
        quantity: Int,
        price: Long,
    ) {
        val line = line(alias)
        line.path("quantity").asInt() shouldBe quantity
        line.minor("priceAtAdd") shouldBe price
        line.minor("currentPrice") shouldBe price
        line.minor("lineTotal") shouldBe price * quantity
    }

    @Then("the cart total is {money}")
    fun theCartTotalIs(total: Long) {
        cart().minor("total") shouldBe total
    }

    @When("the shopper changes the quantity of {string} to {int}")
    fun changesTheQuantity(
        alias: String,
        quantity: Int,
    ) {
        world.carts.setQuantity(world.shopperCart(), lineId(alias), quantity) shouldHaveStatus Status.OK
    }

    @When("the shopper removes the {string} line")
    fun removesTheLine(alias: String) {
        world.carts.remove(world.shopperCart(), lineId(alias)) shouldHaveStatus Status.OK
    }

    @Given("a registered shopper whose account cart holds {int} {string}")
    fun aShopperWhoseAccountCartHolds(
        quantity: Int,
        alias: String,
    ) {
        val shopper = world.accounts.signedInShopper()
        world.shopper = shopper
        val added = world.carts.add(CartHolder(bearer = shopper.bearer), world.product(alias).id, quantity)
        added shouldHaveStatus Status.CREATED
    }

    @Given("the shopper, signed out, has an anonymous cart with {int} {string} and {int} {string}")
    fun signedOutWithAnAnonymousCart(
        firstQuantity: Int,
        first: String,
        secondQuantity: Int,
        second: String,
    ) {
        val shopper = world.theShopper()
        world.api.delete(Paths.CURRENT_SESSION, shopper.bearer) shouldHaveStatus Status.NO_CONTENT
        shopper.accessToken = null
        world.carts.add(world.anonymousCart, world.product(first).id, firstQuantity) shouldHaveStatus Status.CREATED
        world.carts.add(world.anonymousCart, world.product(second).id, secondQuantity) shouldHaveStatus Status.CREATED
    }

    @Then("the account cart holds {int} {string} and {int} {string}")
    fun theAccountCartHolds(
        firstQuantity: Int,
        first: String,
        secondQuantity: Int,
        second: String,
    ) {
        world.carts.view(CartHolder(bearer = world.theShopper().bearer)) shouldHaveStatus Status.OK
        line(first).path("quantity").asInt() shouldBe firstQuantity
        line(second).path("quantity").asInt() shouldBe secondQuantity
    }

    @Then("the shopper is told that the {string} quantity was capped from {int} to {int}")
    fun toldAboutCapping(
        alias: String,
        requested: Int,
        applied: Int,
    ) {
        val merge = checkNotNull(world.mergeResult) { "The shopper did not merge a cart" }
        val capped = merge.body.list("cappedLines").firstOrNull { it.string("productId") == world.product(alias).id }
        withClue("capped lines of the merge: ${merge.describe()}") { capped.shouldNotBeNull() }
        capped?.path("requestedQuantity")?.asInt() shouldBe requested
        capped?.path("appliedQuantity")?.asInt() shouldBe applied
    }

    @When("the shopper views the cart")
    fun viewsTheCart() {
        world.carts.view(world.shopperCart()) shouldHaveStatus Status.OK
    }

    @Given("the shopper has viewed the cart")
    fun hasViewedTheCart() {
        viewsTheCart()
        world.viewedRevision = cart().requireString("revision")
    }

    @Then("the line for {string} shows the current unit price of {money}")
    fun showsTheCurrentPrice(
        alias: String,
        price: Long,
    ) {
        line(alias).minor("currentPrice") shouldBe price
    }

    @Then("the line for {string} is flagged as having changed price")
    fun flaggedAsChanged(alias: String) {
        line(alias).path("priceChanged").asBoolean() shouldBe true
    }

    @Then("the quantity is refused because only {int} units are available")
    fun quantityRefused(available: Int) {
        val refusal = world.api.last
        refusal shouldHaveStatus Status.UNPROCESSABLE
        refusal.body.string("detail").orEmpty() shouldContain available.toString()
    }

    @Then("the cart refuses the product because it is not available")
    fun refusesTheProduct() {
        world.api.last shouldHaveStatus Status.UNPROCESSABLE
    }

    @Then("the cart still holds {int} {string}")
    fun theCartStillHolds(
        quantity: Int,
        alias: String,
    ) {
        world.carts.view(world.shopperCart()) shouldHaveStatus Status.OK
        line(alias).path("quantity").asInt() shouldBe quantity
    }

    @Then("the shopper's cart is empty")
    fun theCartIsEmpty() {
        world.carts.view(world.shopperCart()) shouldHaveStatus Status.OK
        theCartHasNoLines()
    }

    private fun cart(): JsonNode = checkNotNull(world.carts.latest) { "The cart has not been seen yet" }

    private fun line(alias: String): JsonNode =
        checkNotNull(Carts.lineFor(cart(), world.product(alias).id)) { "No line for '$alias' in the cart" }

    private fun lineId(alias: String): String = line(alias).requireString("id")
}

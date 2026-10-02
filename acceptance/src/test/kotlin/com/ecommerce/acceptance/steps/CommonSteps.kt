package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.eventually
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldBeProblem
import com.ecommerce.acceptance.support.shouldHaveStatus
import io.cucumber.java.ParameterType
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/** Steps shared by several features: actors, seeded data, products and stock. */
class CommonSteps(
    private val world: ScenarioWorld,
) {
    /** Money in the feature files is written with two decimals (`25.00`) and handled in minor units. */
    @ParameterType("\\d+\\.\\d{2}")
    fun money(amount: String): Long = amount.replace(".", "").toLong()

    @Given("the seeded catalogue")
    fun theSeededCatalogue() {
        world.catalogue.assertSeeded()
    }

    @Given("an operator is signed in")
    fun anOperatorIsSignedIn() {
        world.operator().shouldNotBeBlank()
    }

    @Given("a registered shopper")
    fun aRegisteredShopper() {
        world.shopper = world.accounts.registeredShopper()
    }

    @Given("a signed-in shopper")
    fun aSignedInShopper() {
        world.shopper = world.accounts.signedInShopper()
    }

    @Given("a signed-in shopper with a saved delivery address")
    fun aSignedInShopperWithAnAddress() {
        world.shopper = world.accounts.signedInShopperWithAddress()
    }

    @Given("another signed-in shopper with a saved delivery address")
    fun anotherSignedInShopperWithAnAddress() {
        world.otherShopper = world.accounts.signedInShopperWithAddress()
    }

    @Given("a product {string} priced at {money} with {int} unit(s) in stock")
    fun aProduct(
        alias: String,
        price: Long,
        stock: Int,
    ) {
        world.remember(world.catalogue.createProduct(alias, price, stock))
    }

    @Given("a product {string} with no stock")
    fun aProductWithNoStock(alias: String) {
        world.remember(world.catalogue.createProduct(alias, DEFAULT_PRICE, 0))
    }

    @Given("a new category {string}")
    fun aNewCategory(alias: String) {
        world.categories[alias] = world.catalogue.createCategory(alias)
    }

    @Given("an anonymous shopper has {int} {string} in the cart")
    fun anAnonymousShopperHas(
        quantity: Int,
        alias: String,
    ) {
        world.carts.add(world.anonymousCart, world.product(alias).id, quantity) shouldHaveStatus Status.CREATED
    }

    @Given("the shopper has {int} {string} in the cart")
    fun theShopperHas(
        quantity: Int,
        alias: String,
    ) {
        world.carts.add(world.shopperCart(), world.product(alias).id, quantity) shouldHaveStatus Status.CREATED
    }

    @Given("the shopper has {int} {string} and {int} {string} in the cart")
    fun theShopperHasTwoProducts(
        firstQuantity: Int,
        first: String,
        secondQuantity: Int,
        second: String,
    ) {
        theShopperHas(firstQuantity, first)
        theShopperHas(secondQuantity, second)
    }

    @When("the shopper signs in")
    fun theShopperSignsIn() {
        val shopper = world.theShopper()
        world.accounts.signInSuccessfully(shopper)
        world.anonymousCart.token?.let { token ->
            world.mergeResult = world.carts.merge(shopper.bearer, token)
            world.mergeResult?.let { it shouldHaveStatus Status.OK }
            world.anonymousCart.token = null
        }
    }

    @Then("{string} has {int} unit(s) left in stock")
    fun unitsLeftInStock(
        alias: String,
        expected: Int,
    ) {
        val product = world.product(alias)
        eventually {
            withClue("available stock of $alias") { world.catalogue.availableQuantity(product.id) shouldBe expected }
        }
    }

    @Given("an operator changes the price of {string} to {money}")
    fun anOperatorChangesThePrice(
        alias: String,
        price: Long,
    ) {
        world.catalogue.changePrice(world.product(alias), price) shouldHaveStatus Status.OK
    }

    @Given("an operator removes all stock of {string}")
    fun anOperatorRemovesAllStock(alias: String) {
        val product = world.product(alias)
        val available = world.catalogue.availableQuantity(product.id)
        world.catalogue.adjustStock(product.id, -available, "Sold elsewhere") shouldHaveStatus Status.CREATED
    }

    @Then("the shopper is refused for lacking the operator role")
    fun refusedForLackingTheOperatorRole() {
        world.api.last.shouldBeProblem(Status.FORBIDDEN, "forbidden")
    }

    @Then("the caller is asked to authenticate")
    fun theCallerIsAskedToAuthenticate() {
        world.api.last shouldHaveStatus Status.UNAUTHORIZED
    }

    @Then("the shopper is asked to sign in first")
    fun theShopperIsAskedToSignIn() {
        theCallerIsAskedToAuthenticate()
        val refusal = world.api.last
        refusal.body.requireString("type").shouldNotBeBlank()
    }

    private companion object {
        const val DEFAULT_PRICE = 1000L
    }
}

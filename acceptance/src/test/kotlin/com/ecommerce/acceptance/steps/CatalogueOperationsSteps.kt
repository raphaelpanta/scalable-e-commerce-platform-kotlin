package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.ApiResponse
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.items
import com.ecommerce.acceptance.support.list
import com.ecommerce.acceptance.support.minor
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldBeProblem
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/** Catalogue and inventory operations by the operator (user story 7, catalog.yaml). */
class CatalogueOperationsSteps(
    private val world: ScenarioWorld,
) {
    private var adjustment: ApiResponse? = null

    @When("the operator creates a product {string} in {string} priced at {money} with {int} units in stock")
    fun theOperatorCreatesAProduct(
        alias: String,
        category: String,
        price: Long,
        stock: Int,
    ) {
        val categoryId = checkNotNull(world.categories[category]) { "No category '$category' in this scenario" }
        world.remember(world.catalogue.createProduct(alias, price, stock, categoryId))
    }

    @Then("an anonymous shopper finds {string} in the {string} category at {money} in stock")
    fun anonymousShopperFindsIt(
        alias: String,
        category: String,
        price: Long,
    ) {
        val listing = world.catalogue.listCategory(checkNotNull(world.categories[category]))
        listing shouldHaveStatus Status.OK
        val found = listing.body.items().firstOrNull { it.string("id") == world.product(alias).id }
        withClue("products of '$category': ${listing.describe()}") { found.shouldNotBeNull() }
        found?.minor("price") shouldBe price
        found?.path("availability")?.path("inStock")?.asBoolean() shouldBe true
    }

    @When("the operator adjusts the stock of {string} by {int} because {string}")
    fun theOperatorAdjustsTheStock(
        alias: String,
        delta: Int,
        reason: String,
    ) {
        adjustment = world.catalogue.adjustStock(world.product(alias).id, delta, reason)
    }

    @Then("the adjustment is recorded with the operator, the time and the reason {string}")
    fun theAdjustmentIsRecorded(reason: String) {
        val recorded = checkNotNull(adjustment) { "No stock adjustment in this scenario" }
        recorded shouldHaveStatus Status.CREATED
        recorded.body.string("reason") shouldBe reason
        recorded.body.string("adjustedBy") shouldBe world.accounts.operatorAccountId()
        val age = Duration.between(Instant.parse(recorded.body.requireString("adjustedAt")), Instant.now()).abs()
        withClue("adjustment time $age away from now") { (age < RECENT) shouldBe true }
    }

    @When("the operator withdraws {string} from sale")
    fun theOperatorWithdraws(alias: String) {
        world.catalogue.withdraw(world.product(alias).id) shouldHaveStatus Status.OK
    }

    @When("the operator withdraws the category {string}")
    fun theOperatorWithdrawsTheCategory(category: String) {
        world.catalogue.withdrawCategory(categoryId(category)) shouldHaveStatus Status.OK
    }

    @When("the operator reinstates the category {string}")
    fun theOperatorReinstatesTheCategory(category: String) {
        world.catalogue.reinstateCategory(categoryId(category)) shouldHaveStatus Status.OK
    }

    @When("the operator reinstates {string}")
    fun theOperatorReinstates(alias: String) {
        val reinstated = world.catalogue.reinstate(world.product(alias).id)
        reinstated shouldHaveStatus Status.OK
        reinstated.body.string("status") shouldBe "active"
    }

    @Then("the operator cannot reinstate {string} while its category is withdrawn")
    fun theOperatorCannotReinstate(alias: String) {
        world.catalogue.reinstate(world.product(alias).id).shouldBeProblem(Status.CONFLICT, "conflict")
        world.catalogue.view(world.product(alias).id) shouldHaveStatus Status.NOT_FOUND
    }

    @Then("the operator cannot create a product in the withdrawn category {string}")
    fun theOperatorCannotCreateAProductIn(category: String) {
        val categoryId = categoryId(category)
        world.catalogue
            .attemptProduct("Refused", REFUSED_PRICE, categoryId)
            .shouldBeProblem(Status.UNPROCESSABLE, "validation")
        world.catalogue.listCategory(categoryId, asOperator = true).body.items().forEach {
            withClue("products of the withdrawn category '$category'") {
                it.requireString("name").startsWith("Refused ") shouldBe false
            }
        }
    }

    @Then("{string} no longer appears when browsing or searching")
    fun noLongerAppears(alias: String) {
        val product = world.product(alias)
        world.catalogue.view(product.id) shouldHaveStatus Status.NOT_FOUND
        world.catalogue.idsOf(world.catalogue.listCategory(product.categoryId).body) shouldNotContain product.id
        world.catalogue.idsOf(world.catalogue.search(product.name).body) shouldNotContain product.id
    }

    @Then("the shopper's order still lists {int} {string} at {money}")
    fun theOrderStillLists(
        quantity: Int,
        alias: String,
        price: Long,
    ) {
        val order = world.orders.get(world.theShopper().bearer, world.orderId())
        order shouldHaveStatus Status.OK
        val line = order.body.list("lines").firstOrNull { it.string("productId") == world.product(alias).id }
        withClue("lines of ${order.describe()}") { line.shouldNotBeNull() }
        line?.path("quantity")?.asInt() shouldBe quantity
        line?.minor("unitPrice") shouldBe price
        order.body.string("orderStatus") shouldBe "placed"
    }

    private fun categoryId(alias: String): String =
        checkNotNull(world.categories[alias]) { "No category '$alias' in this scenario" }

    private companion object {
        val RECENT: Duration = Duration.ofMinutes(5)
        const val REFUSED_PRICE = 1000L
    }
}

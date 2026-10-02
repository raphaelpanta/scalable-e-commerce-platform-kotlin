package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.Catalogue
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.items
import com.ecommerce.acceptance.support.list
import com.ecommerce.acceptance.support.minor
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldBeProblem
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.util.UUID

/** Browsing the catalogue anonymously (user story 1, catalog.yaml). */
class CatalogueSteps(
    private val world: ScenarioWorld,
) {
    private var searchTerm: String = ""

    @Given("a category {string} with these products in stock:")
    fun aCategoryWithProducts(
        category: String,
        products: List<String>,
    ) {
        val categoryId = world.catalogue.createCategory(category)
        world.categories[category] = categoryId
        products.forEachIndexed { index, alias ->
            world.remember(world.catalogue.createProduct(alias, PRICE + index * PRICE, STOCK, categoryId))
        }
    }

    @When("an anonymous shopper lists the products of category {string} two per page")
    fun listsTwoPerPage(category: String) {
        world.catalogue.listCategory(categoryId(category), size = 2) shouldHaveStatus Status.OK
    }

    @Then("the page shows {int} products out of {int} in total")
    fun thePageShows(
        shown: Int,
        total: Int,
    ) {
        val page = world.api.last.body
        page.items().size shouldBe shown
        page.path("totalItems").asInt() shouldBe total
    }

    @Then("every listed product belongs to category {string} and shows its name, price, primary image and availability")
    fun everyListedProductIsComplete(category: String) {
        world.api.last.body.items().forEach { product ->
            withClue("listed product ${product.string("id")}") {
                product.string("categoryId") shouldBe categoryId(category)
                product.requireString("name").shouldNotBeBlank()
                product.minor("price") shouldBeGreaterThan 0L
                product.list("images").count { it.path("primary").asBoolean() } shouldBe 1
                product.path("availability").path("inStock").isBoolean shouldBe true
            }
        }
    }

    @When("an anonymous shopper views the product {string}")
    fun viewsTheProduct(alias: String) {
        world.catalogue.view(world.product(alias).id)
    }

    @Then("it is shown as out of stock")
    fun shownAsOutOfStock() {
        val product = world.api.last
        product shouldHaveStatus Status.OK
        val availability = product.body.path("availability")
        availability.path("inStock").asBoolean() shouldBe false
        withClue("the exact quantity is exposed to operators only") {
            availability.has("availableQuantity") shouldBe false
        }
    }

    @Given("a product {string} named after a new search term")
    fun aProductNamedAfterANewTerm(alias: String) {
        searchTerm = (1..TERM_LENGTH).map { ('a'..'z').random() }.joinToString("")
        val naming = Catalogue.Naming("$searchTerm $alias $searchTerm", "The original $searchTerm.")
        world.remember(world.catalogue.createProduct(alias, PRICE, STOCK, naming = naming))
    }

    @Given("a product {string} that mentions the search term only in its description")
    fun aProductMentioningTheTerm(alias: String) {
        val naming = Catalogue.Naming("$alias ${Catalogue.suffix()}", "Goes well with a $searchTerm.")
        world.remember(world.catalogue.createProduct(alias, PRICE, STOCK, naming = naming))
    }

    @Given("a product {string} named after the search term that an operator has withdrawn")
    fun aWithdrawnProductNamedAfterTheTerm(alias: String) {
        val naming = Catalogue.Naming("$searchTerm $alias", "A former $searchTerm.")
        val product = world.catalogue.createProduct(alias, PRICE, STOCK, naming = naming)
        world.remember(product)
        world.catalogue.withdraw(product.id) shouldHaveStatus Status.OK
    }

    @When("an anonymous shopper searches for the term")
    fun searchesForTheTerm() {
        world.catalogue.search(searchTerm) shouldHaveStatus Status.OK
    }

    @Then("the products {string} and {string} are found in that order")
    fun foundInThatOrder(
        first: String,
        second: String,
    ) {
        val found = world.catalogue.idsOf(world.api.last.body)
        val expected = listOf(world.product(first).id, world.product(second).id)
        found shouldContainAll expected
        withClue("ranking by relevance") { found.indexOf(expected[0]) shouldBeLessThan found.indexOf(expected[1]) }
    }

    @Then("the product {string} is not found")
    fun notFound(alias: String) {
        world.catalogue.idsOf(world.api.last.body) shouldNotContain world.product(alias).id
    }

    @When("an anonymous shopper requests a product that does not exist")
    fun requestsAMissingProduct() {
        world.api.get(Paths.product(UUID.randomUUID().toString()))
    }

    @Then("the shopper is told the product was not found")
    fun toldNotFound() {
        world.api.last.shouldBeProblem(Status.NOT_FOUND, "not-found")
    }

    private fun categoryId(alias: String): String =
        checkNotNull(world.categories[alias]) { "No category '$alias' in this scenario" }

    private companion object {
        const val PRICE = 1000L
        const val STOCK = 5
        const val TERM_LENGTH = 12
    }
}

package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.UUID

private const val OK = 200
private const val BAD_REQUEST = 400
private const val PAGE_OF_TWO = 2
private const val RESERVED = 2
private const val CREATED = 201
private const val STOCK = 5
private const val MIN_CATEGORIES = 3
private const val GARDEN_PRODUCTS = 3
private const val MAX_SIZE = 100
private const val MATCHES = 4
private const val DEFAULT_PRICE = 1990

@Suppress("UNCHECKED_CAST")
private fun Json.items(): List<Json> = this["items"] as List<Json>

private fun Json.ids(): List<String> = items().map { it["id"].toString() }

@Suppress("UNCHECKED_CAST")
private fun Json.availability(): Json = this["availability"] as Json

/**
 * T030: the R2DBC repositories behind the anonymous catalogue reads: category subtree filter, name order, paging
 * with at most 100 items, the case-insensitive search ranked by relevance, withdrawn products hidden from shoppers
 * and the quantity shown to operators only.
 */
class CatalogQueryIT : CatalogIntegrationTest() {
    @Test
    fun `a category lists its own and its descendants' active products by name, page by page`() {
        val garden = category()
        val tools = category("Tools", garden)
        val other = category()
        val spade = product(garden, "Spade")
        val hoe = product(tools, "hoe")
        val rake = product(garden, "Rake")
        product(other, "Pan")
        val withdrawn = product(garden, "Axe")
        call(HttpMethod.POST, "$PRODUCTS/$withdrawn/withdrawal", operator()).expectStatus().isOk

        val first = call(HttpMethod.GET, "$PRODUCTS?categoryId=$garden&size=$PAGE_OF_TWO").json(OK)
        val second = call(HttpMethod.GET, "$PRODUCTS?categoryId=$garden&size=$PAGE_OF_TWO&page=1").json(OK)

        first.ids() shouldContainExactly listOf(hoe, rake)
        second.ids() shouldContainExactly listOf(spade)
        first["totalItems"] shouldBe GARDEN_PRODUCTS
        first["page"] shouldBe 0
        first["size"] shouldBe PAGE_OF_TWO
        second["page"] shouldBe 1
        call(HttpMethod.GET, "$PRODUCTS?categoryId=$tools").json(OK).ids() shouldContainExactly listOf(hoe)
    }

    @Test
    fun `paging accepts at most 100 items and refuses malformed parameters with 400`() {
        call(HttpMethod.GET, "$PRODUCTS?size=$MAX_SIZE").json(OK)["size"] shouldBe MAX_SIZE
        val tooLarge = call(HttpMethod.GET, "$PRODUCTS?size=101").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        tooLarge["errors"] shouldBe listOf(mapOf("field" to "size", "message" to "must be at most 100"))
        call(HttpMethod.GET, "$PRODUCTS?page=-1").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        call(HttpMethod.GET, "$PRODUCTS?page=x").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        call(HttpMethod.GET, "$PRODUCTS?categoryId=nope").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        call(HttpMethod.GET, "$PRODUCTS?includeWithdrawn=maybe").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        call(HttpMethod.GET, "$CATEGORIES?size=0").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        call(
            HttpMethod.GET,
            "$PRODUCTS?q=${"q".repeat(MAX_SIZE + 1)}",
        ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `search matches name and description case-insensitively, ranked, never withdrawn products for shoppers`() {
        val term = "zq" + UUID.randomUUID().toString().take(8)
        val category = category()
        val desk = product(category, "Desk", description = "Goes well with a ${term.uppercase()}.")
        val inName = product(category, "Old $term lamp")
        val prefix = product(category, "${term.uppercase()} lantern")
        val exact = product(category, term)
        val withdrawn = product(category, "$term withdrawn")
        product(category, "Unrelated")
        call(HttpMethod.POST, "$PRODUCTS/$withdrawn/withdrawal", operator()).expectStatus().isOk

        val found = call(HttpMethod.GET, "$PRODUCTS?q=$term").json(OK)

        found.ids() shouldContainExactly listOf(exact, prefix, inName, desk)
        found["totalItems"] shouldBe MATCHES
        call(HttpMethod.GET, "$PRODUCTS?q=$term&includeWithdrawn=true", shopper()).json(OK).ids() shouldNotContain
            withdrawn
        call(HttpMethod.GET, "$PRODUCTS?q=$term&includeWithdrawn=true", operator()).json(OK)["totalItems"] shouldBe
            MATCHES + 1
        call(HttpMethod.GET, "$PRODUCTS?q=${term.dropLast(1)}_").json(OK)["totalItems"] shouldBe 0
        call(HttpMethod.GET, "$PRODUCTS?q=$term&categoryId=${category()}").json(OK)["totalItems"] shouldBe 0
    }

    @Test
    fun `a product shows availability to everyone and the quantity to operators only`() {
        val productId = product(stock = STOCK)
        execute(
            "UPDATE inventory_levels SET reserved = $RESERVED WHERE product_id = :id",
            mapOf("id" to UUID.fromString(productId)),
        )

        val anonymous = call(HttpMethod.GET, "$PRODUCTS/$productId").json(OK)
        val asShopper = call(HttpMethod.GET, "$PRODUCTS/$productId", shopper()).json(OK)
        val asOperator = call(HttpMethod.GET, "$PRODUCTS/$productId", operator()).json(OK)

        anonymous.availability() shouldBe mapOf("inStock" to true)
        anonymous.availability().keys shouldNotContain "availableQuantity"
        asShopper.availability() shouldBe mapOf("inStock" to true)
        asOperator.availability() shouldBe mapOf("inStock" to true, "availableQuantity" to STOCK - RESERVED)
        anonymous["status"] shouldBe "active"
        anonymous["price"] shouldBe mapOf("amountMinor" to DEFAULT_PRICE, "currency" to "BRL")
    }

    @Test
    fun `a product without stock is out of stock, a withdrawn product is hidden from shoppers only`() {
        val soldOut = product(stock = 0)
        val withdrawn = product()
        call(HttpMethod.POST, "$PRODUCTS/$withdrawn/withdrawal", operator()).expectStatus().isOk

        call(HttpMethod.GET, "$PRODUCTS/$soldOut").json(OK).availability() shouldBe mapOf("inStock" to false)
        call(HttpMethod.GET, "$PRODUCTS/$withdrawn").expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.GET, "$PRODUCTS/$withdrawn", shopper()).expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.GET, "$PRODUCTS/$withdrawn", operator()).json(OK)["status"] shouldBe "withdrawn"
        call(HttpMethod.GET, "$PRODUCTS/${UUID.randomUUID()}").expectProblem(ProblemType.NOT_FOUND)["detail"] shouldBe
            "Product not found."
        call(HttpMethod.GET, "$PRODUCTS/not-a-uuid").expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `images are listed with the product, exactly one primary`() {
        val productId = product()
        val first = mapOf("url" to "https://cdn.example.test/a.jpg", "altText" to "Front", "primary" to false)
        val second = mapOf("url" to "https://cdn.example.test/b.jpg", "primary" to true)
        call(HttpMethod.POST, "$PRODUCTS/$productId/images", operator(), first).json(CREATED)
        call(HttpMethod.POST, "$PRODUCTS/$productId/images", operator(), second).json(CREATED)

        @Suppress("UNCHECKED_CAST")
        val images = call(HttpMethod.GET, "$PRODUCTS/$productId").json(OK)["images"] as List<Json>

        images.map { it["url"] } shouldContainExactly listOf(first["url"], second["url"])
        images.map { it["primary"] } shouldContainExactly listOf(false, true)
        images.first()["altText"] shouldBe "Front"
        images.last().keys shouldNotContain "altText"
    }

    @Test
    fun `categories are listed by name, optionally the children of one parent, and found by id`() {
        val parent = category("Parent ${UUID.randomUUID()}")
        val zeta = category("Zeta", parent)
        val alpha = category("alpha", parent)

        val children = call(HttpMethod.GET, "$CATEGORIES?parentId=$parent").json(OK)

        children.ids() shouldContainExactly listOf(alpha, zeta)
        children["totalItems"] shouldBe 2
        call(HttpMethod.GET, "$CATEGORIES/$zeta").json(OK) shouldBe
            mapOf("id" to zeta, "name" to "Zeta", "parentId" to parent)
        call(HttpMethod.GET, "$CATEGORIES/${UUID.randomUUID()}").expectProblem(ProblemType.NOT_FOUND)
        (call(HttpMethod.GET, "$CATEGORIES?size=1").json(OK)["totalItems"] as Int >= MIN_CATEGORIES) shouldBe true
    }
}

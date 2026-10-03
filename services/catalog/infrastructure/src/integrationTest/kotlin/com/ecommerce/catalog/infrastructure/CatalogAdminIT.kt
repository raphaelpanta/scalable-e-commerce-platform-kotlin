package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val BAD_REQUEST = 400
private const val UNPROCESSABLE = 422
private const val PRICE = 12000
private const val STOCK = 10
private const val REMOVED = -3
private const val ADDED = 5
private val RECENT: Duration = Duration.ofMinutes(1)

/** The stock of a product created by the `product` helper without one. */
private const val STOCK_OF_HELPER = 5

@Suppress("UNCHECKED_CAST")
private fun Json.ids(): List<String> = (this["items"] as List<Json>).map { it["id"].toString() }

/**
 * The operator writes of catalog.yaml with operator, shopper and anonymous tokens (US7): 401 without a token, 403 and
 * an audit line for shoppers, attributed changes for operators, 422 for broken rules and 409 for conflicts.
 */
@ExtendWith(OutputCaptureExtension::class)
class CatalogAdminIT : CatalogIntegrationTest() {
    private fun productBody(
        categoryId: String,
        name: String = "Camping stove",
    ) = mapOf(
        "name" to name,
        "description" to "Burns gas.",
        "price" to mapOf("amountMinor" to PRICE, "currency" to "BRL"),
        "categoryId" to categoryId,
        "initialStock" to STOCK,
    )

    @Test
    fun `an operator creates a product visible to shoppers at once, with its location`() {
        val categoryId = category()

        val result =
            call(HttpMethod.POST, PRODUCTS, operator(), productBody(categoryId))
                .expectStatus()
                .isEqualTo(CREATED)
                .expectBody(JSON_OBJECT)
                .returnResult()
        val created = checkNotNull(result.responseBody)

        result.responseHeaders.location.toString() shouldBe "$PRODUCTS/${created["id"]}"
        created["availability"] shouldBe mapOf("inStock" to true, "availableQuantity" to STOCK)
        created["sku"].toString().startsWith("P-") shouldBe true
        val seen = call(HttpMethod.GET, "$PRODUCTS/${created["id"]}").json(OK)
        seen["name"] shouldBe "Camping stove"
        seen["availability"] shouldBe mapOf("inStock" to true)
    }

    @Test
    fun `writes need a token, and shoppers are refused with 403 and an audit line without personal data`(
        output: CapturedOutput,
    ) {
        val categoryId = category()
        val productId = product(categoryId)
        val attempts =
            listOf(
                Triple(HttpMethod.POST, PRODUCTS, productBody(categoryId)),
                Triple(HttpMethod.PUT, "$PRODUCTS/$productId", productBody(categoryId)),
                Triple(HttpMethod.POST, "$PRODUCTS/$productId/withdrawal", null),
                Triple(
                    HttpMethod.POST,
                    "$PRODUCTS/$productId/stock-adjustments",
                    mapOf(
                        "delta" to 1,
                        "reason" to "Delivery",
                    ),
                ),
                Triple(
                    HttpMethod.POST,
                    "$PRODUCTS/$productId/images",
                    mapOf("url" to "https://cdn.example.test/x.jpg"),
                ),
                Triple(HttpMethod.POST, CATEGORIES, mapOf("name" to "Shopper category")),
                Triple(HttpMethod.PUT, "$CATEGORIES/$categoryId", mapOf("name" to "Renamed")),
                Triple(HttpMethod.POST, "$CATEGORIES/$categoryId/withdrawal", null),
            )

        attempts.forEach { (method, uri, body) ->
            call(method, uri, null, body).expectProblem(ProblemType.UNAUTHORIZED)
            call(method, uri, shopper(), body).expectProblem(ProblemType.FORBIDDEN)["detail"] shouldBe
                "Operator role required."
        }

        val refusals = output.out.lines().filter { it.contains("\"outcome\":\"refused\"") }
        refusals shouldHaveSize attempts.size
        refusals.first() shouldContain "\"action\":\"createProduct\""
        refusals.first() shouldContain "\"accountId\":\"$SHOPPER_ID\""
        refusals.first() shouldContain "\"correlationId\":\""
        refusals.forEach { it shouldNotContain "Bearer" }
        stockOf(productId) shouldBe "5/0"
        call(HttpMethod.GET, "$PRODUCTS/$productId").json(OK)["status"] shouldBe "active"
    }

    @Test
    fun `an update replaces the details, keeps the stock and validates the rules`() {
        val categoryId = category()
        val productId = product(categoryId, stock = STOCK)

        val updated =
            call(
                HttpMethod.PUT,
                "$PRODUCTS/$productId",
                operator(),
                productBody(category(), "Stove"),
            ).json(OK)

        updated["name"] shouldBe "Stove"
        updated["availability"] shouldBe mapOf("inStock" to true, "availableQuantity" to STOCK)
        val invalid =
            call(HttpMethod.PUT, "$PRODUCTS/$productId", operator(), productBody(categoryId, "x".repeat(121)))
                .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        invalid["errors"] shouldBe listOf(mapOf("field" to "name", "message" to "must be at most 120 characters"))
        call(HttpMethod.PUT, "$PRODUCTS/$productId", operator(), productBody(UUID.randomUUID().toString()))
            .expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.PUT, "$PRODUCTS/${UUID.randomUUID()}", operator(), productBody(categoryId))
            .expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.PUT, "$PRODUCTS/$productId", operator(), mapOf("name" to "No price"))
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `withdrawing hides the product from browsing and search, withdrawing again is a conflict`() {
        val name = "Hammock ${UUID.randomUUID()}"
        val categoryId = category()
        val productId = product(categoryId, name)

        call(HttpMethod.POST, "$PRODUCTS/$productId/withdrawal", operator()).json(OK)["status"] shouldBe "withdrawn"

        call(HttpMethod.GET, "$PRODUCTS/$productId").expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.GET, "$PRODUCTS?categoryId=$categoryId").json(OK)["totalItems"] shouldBe 0
        call(HttpMethod.GET, "$PRODUCTS?q=$name").json(OK)["totalItems"] shouldBe 0
        call(HttpMethod.POST, "$PRODUCTS/$productId/withdrawal", operator()).expectProblem(ProblemType.CONFLICT)
        internal(HttpMethod.GET, "/internal/products/$productId/pricing").json(OK)["saleState"] shouldBe "withdrawn"
    }

    @Test
    fun `withdrawing a category hides it, its subcategories and their products from shoppers, not from operators`() {
        val marker = UUID.randomUUID().toString()
        val outdoor = category("Outdoor $marker")
        val tents = category("Tents", outdoor)
        val tentId = product(tents, "Tent $marker")
        val otherId = product(category(), "Lamp $marker")

        val withdrawn = call(HttpMethod.POST, "$CATEGORIES/$outdoor/withdrawal", operator()).json(OK)

        withdrawn shouldBe mapOf("id" to outdoor, "name" to "Outdoor $marker", "status" to "withdrawn")
        call(HttpMethod.POST, "$CATEGORIES/$outdoor/withdrawal", operator()).expectProblem(ProblemType.CONFLICT)
        call(HttpMethod.POST, "$CATEGORIES/${UUID.randomUUID()}/withdrawal", operator())
            .expectProblem(ProblemType.NOT_FOUND)
        // Shoppers: the subtree and its products are gone from browsing, search and direct reads.
        call(HttpMethod.GET, "$PRODUCTS?q=$marker").json(OK).ids() shouldBe listOf(otherId)
        call(HttpMethod.GET, "$PRODUCTS?categoryId=$outdoor").json(OK)["totalItems"] shouldBe 0
        call(HttpMethod.GET, "$PRODUCTS/$tentId", shopper()).expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.GET, "$CATEGORIES/$tents").expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.GET, "$CATEGORIES?parentId=$outdoor").json(OK)["totalItems"] shouldBe 0
        internal(HttpMethod.GET, "/internal/products/$tentId/pricing").json(OK)["saleState"] shouldBe "withdrawn"
        // Operators still see everything, with the state.
        call(HttpMethod.GET, "$CATEGORIES/$tents", operator()).json(OK)["status"] shouldBe "active"
        call(HttpMethod.GET, "$CATEGORIES/$outdoor", operator()).json(OK)["status"] shouldBe "withdrawn"
        call(HttpMethod.GET, "$PRODUCTS?q=$marker&includeWithdrawn=true", operator()).json(OK)["totalItems"] shouldBe 2
        with(call(HttpMethod.GET, "$PRODUCTS/$tentId", operator()).json(OK)) {
            this["status"] shouldBe "active"
            this["availability"] shouldBe mapOf("inStock" to false, "availableQuantity" to STOCK_OF_HELPER)
        }
        // No product can be placed in the withdrawn subtree.
        val refused =
            call(HttpMethod.POST, PRODUCTS, operator(), productBody(tents)).expectProblem(
                ProblemType.VALIDATION,
                UNPROCESSABLE,
            )
        refused["errors"] shouldBe listOf(mapOf("field" to "categoryId", "message" to "must be an active category"))
        call(HttpMethod.PUT, "$PRODUCTS/$otherId", operator(), productBody(outdoor))
            .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
    }

    @Test
    fun `stock adjustments are applied and recorded with who, when and why`() {
        val productId = product(stock = STOCK)

        val removed =
            call(
                HttpMethod.POST,
                "$PRODUCTS/$productId/stock-adjustments",
                operator(),
                mapOf("delta" to REMOVED, "reason" to "Damaged during stocktake"),
            ).json(CREATED)
        val added =
            call(
                HttpMethod.POST,
                "$PRODUCTS/$productId/stock-adjustments",
                operator(),
                mapOf("delta" to ADDED, "reason" to "Delivery received"),
            ).json(CREATED)

        removed["previousQuantity"] shouldBe STOCK
        removed["newQuantity"] shouldBe STOCK + REMOVED
        removed["reason"] shouldBe "Damaged during stocktake"
        removed["adjustedBy"] shouldBe OPERATOR_ID.toString()
        removed["productId"] shouldBe productId
        (Duration.between(Instant.parse(removed["adjustedAt"].toString()), Instant.now()).abs() < RECENT) shouldBe true
        added["newQuantity"] shouldBe STOCK + REMOVED + ADDED
        stockOf(productId) shouldBe "12/0"
        val recorded =
            database
                .sql("SELECT count(*) AS n FROM stock_adjustments WHERE product_id = :id AND actor_id = :actor")
                .bind("id", UUID.fromString(productId))
                .bind("actor", OPERATOR_ID)
                .map { row -> row.get("n", Long::class.javaObjectType) ?: 0L }
                .one()
                .block(Duration.ofSeconds(1))
        recorded shouldBe 2L
    }

    @Test
    fun `an adjustment needs a non-zero delta and a reason and cannot take available stock below zero`() {
        val productId = product(stock = 2)
        execute(
            "UPDATE inventory_levels SET reserved = 1 WHERE product_id = :id",
            mapOf("id" to UUID.fromString(productId)),
        )
        val uri = "$PRODUCTS/$productId/stock-adjustments"

        val belowReserved =
            call(HttpMethod.POST, uri, operator(), mapOf("delta" to -2, "reason" to "Lost"))
                .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        belowReserved["errors"] shouldBe
            listOf(mapOf("field" to "delta", "message" to "would make available quantity negative"))
        val invalid =
            call(HttpMethod.POST, uri, operator(), mapOf("delta" to 0, "reason" to "x"))
                .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        @Suppress("UNCHECKED_CAST")
        (invalid["errors"] as List<Json>).map { it["field"] } shouldBe listOf("delta", "reason")
        call(
            HttpMethod.POST,
            uri,
            operator(),
            mapOf("reason" to "No delta"),
        ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        stockOf(productId) shouldBe "2/1"
    }

    @Test
    fun `categories are created and moved by operators, duplicates conflict and cycles are refused`() {
        val name = "Outdoor ${UUID.randomUUID()}"
        val outdoor = category(name)
        val tents = category("Tents", outdoor)
        val other = category()

        call(
            HttpMethod.POST,
            CATEGORIES,
            operator(),
            mapOf("name" to name.uppercase()),
        ).expectProblem(ProblemType.CONFLICT)
        call(HttpMethod.POST, CATEGORIES, operator(), mapOf("name" to "Tents", "parentId" to other)).json(CREATED)
        call(HttpMethod.POST, CATEGORIES, operator(), mapOf("name" to "x", "parentId" to UUID.randomUUID().toString()))
            .expectProblem(ProblemType.NOT_FOUND)
        call(HttpMethod.PUT, "$CATEGORIES/$outdoor", operator(), mapOf("name" to name, "parentId" to tents))
            .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        val moved =
            call(
                HttpMethod.PUT,
                "$CATEGORIES/$tents",
                operator(),
                mapOf(
                    "name" to "Tents 2P",
                    "parentId" to other,
                ),
            ).json(OK)
        moved shouldBe mapOf("id" to tents, "name" to "Tents 2P", "parentId" to other, "status" to "active")
        call(HttpMethod.PUT, "$CATEGORIES/$tents", operator(), mapOf("name" to "Tents", "parentId" to other))
            .expectProblem(ProblemType.CONFLICT)
    }

    @Test
    fun `images are registered with their metadata, a product holds at most ten`() {
        val productId = product()
        val image =
            call(
                HttpMethod.POST,
                "$PRODUCTS/$productId/images",
                operator(),
                mapOf("url" to "https://cdn.example.test/side.jpg", "altText" to "Side view"),
            ).json(CREATED)

        image["primary"] shouldBe true
        image["altText"] shouldBe "Side view"
        repeat(MAX_IMAGES - 1) {
            call(
                HttpMethod.POST,
                "$PRODUCTS/$productId/images",
                operator(),
                mapOf("url" to "img/$it.jpg"),
            ).json(CREATED)
        }
        call(HttpMethod.POST, "$PRODUCTS/$productId/images", operator(), mapOf("url" to "img/x.jpg"))
            .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
        call(HttpMethod.POST, "$PRODUCTS/$productId/images", operator(), mapOf("url" to "ftp://x"))
            .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)
    }

    private companion object {
        const val MAX_IMAGES = 10
    }
}

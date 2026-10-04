package com.ecommerce.acceptance.support

import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import java.util.UUID

/** A product created for one scenario, known in the feature files by its [alias]. */
class ProductRef(
    val alias: String,
    val id: String,
    val name: String,
    val categoryId: String,
    var priceMinor: Long,
)

/** Catalogue helpers (catalog.yaml): seeded data for browsing, operator-created products for everything else. */
class Catalogue(
    private val api: ApiClient,
    private val accounts: Accounts,
) {
    private var scenarioCategory: String? = null

    /** Creates a category with a unique name and returns its id. */
    fun createCategory(name: String): String {
        val response = api.post(Paths.CATEGORIES, mapOf("name" to "$name ${suffix()}"), accounts.operatorToken())
        response shouldHaveStatus Status.CREATED
        return response.body.requireString("id")
    }

    /** A category of this scenario, created on first use, so that its products are isolated from others. */
    fun scenarioCategory(): String = scenarioCategory ?: createCategory("Acceptance").also { scenarioCategory = it }

    /** Creates an active product with a primary image; the name is [name] or the alias made unique. */
    fun createProduct(
        alias: String,
        priceMinor: Long,
        stock: Int,
        categoryId: String = scenarioCategory(),
        naming: Naming = Naming(),
    ): ProductRef {
        val name = naming.name ?: "$alias ${suffix()}"
        val body = productBody(name, naming.description, priceMinor, categoryId, stock)
        val response = api.post(Paths.PRODUCTS, body, operator())
        response shouldHaveStatus Status.CREATED
        val product = ProductRef(alias, response.body.requireString("id"), name, categoryId, priceMinor)
        val image = mapOf("url" to "https://cdn.example.test/${product.id}.jpg", "altText" to alias, "primary" to true)
        api.post(Paths.images(product.id), image, operator()) shouldHaveStatus Status.CREATED
        return product
    }

    /** The product as seen by an anonymous shopper, or by the operator when [asOperator]. */
    fun view(
        productId: String,
        asOperator: Boolean = false,
    ): ApiResponse = api.get(Paths.product(productId), if (asOperator) operator() else null)

    /** Available quantity, which only the operator role can read (`availability.availableQuantity`). */
    fun availableQuantity(productId: String): Int {
        val response = view(productId, asOperator = true)
        response shouldHaveStatus Status.OK
        val availability = response.body.path("availability")
        return availability.path("availableQuantity").asInt()
    }

    fun adjustStock(
        productId: String,
        delta: Int,
        reason: String,
    ): ApiResponse {
        val body = mapOf("delta" to delta, "reason" to reason)
        return api.post(Paths.stockAdjustments(productId), body, operator())
    }

    /** Replaces the product's price, keeping its name, description and category (`updateProduct`). */
    fun changePrice(
        product: ProductRef,
        priceMinor: Long,
    ): ApiResponse {
        val current = view(product.id, asOperator = true).body
        val body =
            mapOf(
                "name" to current.requireString("name"),
                "description" to current.string("description").orEmpty(),
                "price" to money(priceMinor),
                "categoryId" to current.requireString("categoryId"),
            )
        return api.put(Paths.product(product.id), body, operator()).also {
            if (it.status == Status.OK) product.priceMinor = priceMinor
        }
    }

    fun withdraw(productId: String): ApiResponse = api.post(Paths.withdrawal(productId), null, operator())

    fun reinstate(productId: String): ApiResponse = api.post(Paths.reinstatement(productId), null, operator())

    fun withdrawCategory(categoryId: String): ApiResponse =
        api.post(Paths.categoryWithdrawal(categoryId), null, operator())

    fun reinstateCategory(categoryId: String): ApiResponse =
        api.post(Paths.categoryReinstatement(categoryId), null, operator())

    /** The operator's attempt to create a product in [categoryId], without asserting the outcome. */
    fun attemptProduct(
        name: String,
        priceMinor: Long,
        categoryId: String,
    ): ApiResponse {
        val body = productBody("$name ${suffix()}", "Must not be created.", priceMinor, categoryId, 1)
        return api.post(Paths.PRODUCTS, body, operator())
    }

    /** Anonymous listing of one category, or the operator's (withdrawn products included) when [asOperator]. */
    fun listCategory(
        categoryId: String,
        size: Int = MAX_PAGE,
        asOperator: Boolean = false,
    ): ApiResponse {
        val parameters = listOf("categoryId" to categoryId, "page" to 0, "size" to size)
        val withdrawn = if (asOperator) listOf("includeWithdrawn" to true) else emptyList()
        return api.get(Paths.query(Paths.PRODUCTS, *(parameters + withdrawn).toTypedArray()), operatorIf(asOperator))
    }

    private fun operatorIf(asOperator: Boolean): String? = if (asOperator) operator() else null

    /** Anonymous free-text search. */
    fun search(term: String): ApiResponse = api.get(Paths.query(Paths.PRODUCTS, "q" to term, "size" to MAX_PAGE))

    /** Ids of the products of a page response. */
    fun idsOf(page: JsonNode): List<String> = page.items().map { it.requireString("id") }

    /** The seeded categories and products must exist (`SEED=true`). */
    fun assertSeeded() {
        val categories = api.get(Paths.query(Paths.CATEGORIES, "size" to 1))
        categories shouldHaveStatus Status.OK
        (categories.body.path("totalItems").asLong() > 0) shouldBe true
        val products = api.get(Paths.query(Paths.PRODUCTS, "size" to 1))
        products shouldHaveStatus Status.OK
        (products.body.path("totalItems").asLong() > 0) shouldBe true
    }

    private fun operator() = accounts.operatorToken()

    /** Optional fixed name and description of a product, for search scenarios. */
    class Naming(
        val name: String? = null,
        val description: String = "Created by the acceptance suite.",
    )

    companion object {
        const val MAX_PAGE = 100

        fun suffix(): String = UUID.randomUUID().toString().take(SUFFIX_LENGTH)

        fun productBody(
            name: String,
            description: String,
            priceMinor: Long,
            categoryId: String,
            stock: Int,
        ): Map<String, Any> =
            mapOf(
                "name" to name,
                "description" to description,
                "price" to money(priceMinor),
                "categoryId" to categoryId,
                "initialStock" to stock,
            )

        private const val SUFFIX_LENGTH = 8
    }
}

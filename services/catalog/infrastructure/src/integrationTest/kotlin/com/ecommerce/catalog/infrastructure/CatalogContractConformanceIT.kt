package com.ecommerce.catalog.infrastructure

import com.ecommerce.conformance.OpenApiContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val UNPROCESSABLE = 422

/**
 * Every public operation of `contracts/openapi/catalog.yaml`, with its success and documented error statuses,
 * exercised against the running service and validated request by request (pact-matrix rule 3, constitution
 * Principle V). Statuses the service cannot produce on its own are deferred with the reason.
 */
class CatalogContractConformanceIT : CatalogIntegrationTest() {
    private val contract = OpenApiContract.of("catalog")

    @Test
    fun `the public operations conform to catalog yaml`() {
        val marker = UUID.randomUUID().toString()
        categories(marker)
        products(marker)
        contract.verify(DEFERRED)
    }

    private fun categories(marker: String) {
        val name = "Conformance $marker"
        val root = contract.check(call(POST, CATEGORIES, operator(), mapOf("name" to name)), CREATED)["id"].toString()
        val child =
            contract
                .check(call(POST, CATEGORIES, operator(), mapOf("name" to "Child", "parentId" to root)), CREATED)["id"]
                .toString()
        contract.check(call(POST, CATEGORIES, body = mapOf("name" to "Anonymous")), UNAUTHORIZED)
        contract.check(call(POST, CATEGORIES, shopper(), mapOf("name" to "Shopper")), FORBIDDEN)
        contract.check(call(POST, CATEGORIES, operator(), mapOf("description" to "no name")), BAD_REQUEST)
        contract.check(call(POST, CATEGORIES, operator(), mapOf("name" to "x".repeat(LONG_NAME))), UNPROCESSABLE)
        contract.check(call(POST, CATEGORIES, operator(), mapOf("name" to name.uppercase())), CONFLICT)
        contract.check(
            call(POST, CATEGORIES, operator(), mapOf("name" to "Orphan", "parentId" to UUID.randomUUID().toString())),
            NOT_FOUND,
        )

        contract.check(call(GET, "$CATEGORIES?parentId=$root"), OK)
        contract.check(call(GET, "$CATEGORIES?size=0"), BAD_REQUEST)
        contract.check(call(GET, "$CATEGORIES/$root"), OK)
        contract.check(call(GET, "$CATEGORIES/${UUID.randomUUID()}"), NOT_FOUND)

        val sibling = category("Sibling $marker")
        contract.check(call(PUT, "$CATEGORIES/$child", operator(), mapOf("name" to "Renamed", "parentId" to root)), OK)
        contract.check(call(PUT, "$CATEGORIES/$child", body = mapOf("name" to "Anonymous")), UNAUTHORIZED)
        contract.check(call(PUT, "$CATEGORIES/$child", shopper(), mapOf("name" to "Shopper")), FORBIDDEN)
        contract.check(call(PUT, "$CATEGORIES/$child", operator(), mapOf("parentId" to root)), BAD_REQUEST)
        contract.check(call(PUT, "$CATEGORIES/${UUID.randomUUID()}", operator(), mapOf("name" to "Gone")), NOT_FOUND)
        contract.check(call(PUT, "$CATEGORIES/$sibling", operator(), mapOf("name" to name)), CONFLICT)
        contract.check(
            call(PUT, "$CATEGORIES/$root", operator(), mapOf("name" to name, "parentId" to child)),
            UNPROCESSABLE,
        )

        contract.check(call(POST, "$CATEGORIES/$sibling/withdrawal"), UNAUTHORIZED)
        contract.check(call(POST, "$CATEGORIES/$sibling/withdrawal", shopper()), FORBIDDEN)
        contract.check(call(POST, "$CATEGORIES/not-a-uuid/withdrawal", operator()), BAD_REQUEST)
        contract.check(call(POST, "$CATEGORIES/${UUID.randomUUID()}/withdrawal", operator()), NOT_FOUND)
        contract.check(call(POST, "$CATEGORIES/$sibling/withdrawal", operator()), OK)
        contract.check(call(POST, "$CATEGORIES/$sibling/withdrawal", operator()), CONFLICT)

        contract.check(call(POST, "$CATEGORIES/$sibling/reinstatement"), UNAUTHORIZED)
        contract.check(call(POST, "$CATEGORIES/$sibling/reinstatement", shopper()), FORBIDDEN)
        contract.check(call(POST, "$CATEGORIES/not-a-uuid/reinstatement", operator()), BAD_REQUEST)
        contract.check(call(POST, "$CATEGORIES/${UUID.randomUUID()}/reinstatement", operator()), NOT_FOUND)
        contract.check(call(POST, "$CATEGORIES/$sibling/reinstatement", operator()), OK)
        contract.check(call(POST, "$CATEGORIES/$sibling/reinstatement", operator()), CONFLICT)
    }

    private fun products(marker: String) {
        val categoryId = category("Products $marker")
        val body = productBody(categoryId, "Lantern $marker")
        val productId = contract.check(call(POST, PRODUCTS, operator(), body + ("initialStock" to 3)), CREATED)["id"]
        contract.check(call(POST, PRODUCTS, body = body), UNAUTHORIZED)
        contract.check(call(POST, PRODUCTS, shopper(), body), FORBIDDEN)
        contract.check(call(POST, PRODUCTS, operator(), mapOf("name" to "No price")), BAD_REQUEST)
        contract.check(call(POST, PRODUCTS, operator(), body + ("name" to "x".repeat(LONG_PRODUCT))), UNPROCESSABLE)
        contract.check(
            call(POST, PRODUCTS, operator(), body + ("categoryId" to UUID.randomUUID().toString())),
            NOT_FOUND,
        )

        contract.check(call(GET, "$PRODUCTS?q=Lantern&categoryId=$categoryId"), OK)
        contract.check(call(GET, "$PRODUCTS?includeWithdrawn=true", operator()), OK)
        contract.check(call(GET, "$PRODUCTS?size=101"), BAD_REQUEST)
        contract.check(call(GET, "$PRODUCTS/$productId"), OK)
        contract.check(call(GET, "$PRODUCTS/$productId", operator()), OK)
        contract.check(call(GET, "$PRODUCTS/${UUID.randomUUID()}"), NOT_FOUND)

        val item = "$PRODUCTS/$productId"
        contract.check(call(PUT, item, operator(), productBody(categoryId, "Lantern 2 $marker")), OK)
        contract.check(call(PUT, item, body = body), UNAUTHORIZED)
        contract.check(call(PUT, item, shopper(), body), FORBIDDEN)
        contract.check(call(PUT, item, operator(), mapOf("name" to "No price")), BAD_REQUEST)
        contract.check(call(PUT, "$PRODUCTS/${UUID.randomUUID()}", operator(), body), NOT_FOUND)
        contract.check(call(PUT, item, operator(), productBody(categoryId, "x".repeat(LONG_PRODUCT))), UNPROCESSABLE)

        inventoryAndImages(item, marker)

        val withdrawal = "$item/withdrawal"
        contract.check(call(POST, withdrawal), UNAUTHORIZED)
        contract.check(call(POST, withdrawal, shopper()), FORBIDDEN)
        contract.check(call(POST, "$PRODUCTS/${UUID.randomUUID()}/withdrawal", operator()), NOT_FOUND)
        contract.check(call(POST, withdrawal, operator()), OK)
        contract.check(call(POST, withdrawal, operator()), CONFLICT)

        reinstatement(item, categoryId)
    }

    /** Reinstating the withdrawn product at [item]: refused while its category is withdrawn, then once only. */
    private fun reinstatement(
        item: String,
        categoryId: String,
    ) {
        val reinstatement = "$item/reinstatement"
        contract.check(call(POST, reinstatement), UNAUTHORIZED)
        contract.check(call(POST, reinstatement, shopper()), FORBIDDEN)
        contract.check(call(POST, "$PRODUCTS/not-a-uuid/reinstatement", operator()), BAD_REQUEST)
        contract.check(call(POST, "$PRODUCTS/${UUID.randomUUID()}/reinstatement", operator()), NOT_FOUND)
        contract.check(call(POST, "$CATEGORIES/$categoryId/withdrawal", operator()), OK)
        contract.check(call(POST, reinstatement, operator()), CONFLICT)
        contract.check(call(POST, "$CATEGORIES/$categoryId/reinstatement", operator()), OK)
        contract.check(call(POST, reinstatement, operator()), OK)
        contract.check(call(POST, reinstatement, operator()), CONFLICT)
    }

    private fun inventoryAndImages(
        item: String,
        marker: String,
    ) {
        val adjustments = "$item/stock-adjustments"
        contract.check(call(POST, adjustments, operator(), mapOf("delta" to 2, "reason" to "Delivery")), CREATED)
        contract.check(call(POST, adjustments, body = mapOf("delta" to 1, "reason" to "Restock")), UNAUTHORIZED)
        contract.check(call(POST, adjustments, shopper(), mapOf("delta" to 1, "reason" to "Restock")), FORBIDDEN)
        contract.check(call(POST, adjustments, operator(), mapOf("reason" to "No delta")), BAD_REQUEST)
        val restock = mapOf("delta" to 1, "reason" to "Restock")
        contract.check(call(POST, "$PRODUCTS/${UUID.randomUUID()}/stock-adjustments", operator(), restock), NOT_FOUND)
        contract.check(call(POST, adjustments, operator(), mapOf("delta" to -LOTS, "reason" to "Lost")), UNPROCESSABLE)

        val images = "$item/images"
        val image = mapOf("url" to "https://cdn.example.test/$marker.jpg", "altText" to "Front")
        contract.check(call(POST, images, operator(), image), CREATED)
        contract.check(call(POST, images, body = image), UNAUTHORIZED)
        contract.check(call(POST, images, shopper(), image), FORBIDDEN)
        contract.check(call(POST, images, operator(), mapOf("altText" to "No URL")), BAD_REQUEST)
        contract.check(call(POST, "$PRODUCTS/${UUID.randomUUID()}/images", operator(), image), NOT_FOUND)
        contract.check(call(POST, images, operator(), mapOf("url" to "ftp://x")), UNPROCESSABLE)
    }

    private fun productBody(
        categoryId: String,
        name: String,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "description" to "Checked against the contract.",
            "price" to mapOf("amountMinor" to PRICE, "currency" to "BRL"),
            "categoryId" to categoryId,
        )

    private companion object {
        const val LONG_NAME = 121
        const val LONG_PRODUCT = 121
        const val PRICE = 4990
        const val LOTS = 1000

        /** Documented statuses this layer cannot produce, with the reason. */
        val DEFERRED: Map<String, String> =
            mapOf(
                "* 429" to "rate limiting is the gateway's (contracts/gateway-routes.md), covered by its tests",
                "updateProduct 409" to "a concurrent update (stale version) cannot be produced deterministically",
            )
    }
}

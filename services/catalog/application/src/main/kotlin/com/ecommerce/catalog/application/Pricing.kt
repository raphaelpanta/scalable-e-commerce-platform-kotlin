package com.ecommerce.catalog.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.SaleState

/**
 * Current price, name and availability of a product (catalog-internal.yaml `ProductPricing`), withdrawn or not. The
 * [saleState] is the one carts must honour: a product of a withdrawn category is reported withdrawn (FR-003).
 */
data class ProductPricing(
    val product: Product,
    val available: Int,
    val saleState: SaleState = product.saleState,
)

/** The pricing of [product] given the [hidden] categories. */
private fun pricing(
    product: Product,
    available: Int,
    hidden: Set<CategoryId>,
): ProductPricing =
    ProductPricing(product, available, if (product.onSale(hidden)) SaleState.ACTIVE else SaleState.WITHDRAWN)

/** `getProductPricing`: withdrawn products are answered too; only an unknown product is not found. */
class GetPricing(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(productId: ProductId): Either<CatalogError, ProductPricing> {
        val product = catalog.products.find(productId) ?: return CatalogError.ProductNotFound(productId).left()
        val available = catalog.availability(listOf(product)).getValue(productId)
        return pricing(product, available, catalog.hiddenCategories()).right()
    }
}

/** `getProductsPricing`: up to 100 distinct ids; unknown ids are omitted, the others follow the request order. */
class GetPricingBatch(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(productIds: List<ProductId>): Either<CatalogError, List<ProductPricing>> {
        if (productIds.isEmpty() || productIds.size > MAX_IDS || productIds.toSet().size != productIds.size) {
            return FieldIssue("productIds", "must hold 1 to $MAX_IDS distinct ids").invalid().left()
        }
        val known = catalog.products.findAll(productIds).associateBy(Product::id)
        val available = catalog.availability(known.values)
        val hidden = catalog.hiddenCategories()
        return productIds
            .mapNotNull { id -> known[id]?.let { pricing(it, available.getValue(id), hidden) } }
            .right()
    }

    companion object {
        const val MAX_IDS: Int = 100
    }
}

package com.ecommerce.catalog.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductId

/** Current price, name and availability of a product (catalog-internal.yaml `ProductPricing`), withdrawn or not. */
data class ProductPricing(
    val product: Product,
    val available: Int,
)

/** `getProductPricing`: withdrawn products are answered too; only an unknown product is not found. */
class GetPricing(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(productId: ProductId): Either<CatalogError, ProductPricing> {
        val product = catalog.products.find(productId) ?: return CatalogError.ProductNotFound(productId).left()
        return ProductPricing(product, catalog.availability(listOf(product)).getValue(productId)).right()
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
        return productIds
            .mapNotNull { id -> known[id]?.let { ProductPricing(it, available.getValue(id)) } }
            .right()
    }

    companion object {
        const val MAX_IDS: Int = 100
    }
}

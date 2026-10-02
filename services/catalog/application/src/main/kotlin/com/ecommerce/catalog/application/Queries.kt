package com.ecommerce.catalog.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.SearchTerm

/**
 * A product as one caller sees it: its [available] quantity decides `inStock`, and only an operator
 * ([showQuantity]) is told the quantity itself (catalog.yaml `Availability`).
 */
data class ProductView(
    val product: Product,
    val available: Int,
    val showQuantity: Boolean,
) {
    /** True when the product is on sale with at least one unit available. */
    val inStock: Boolean get() = product.isActive && available > 0
}

/** The page of products of [filter] with their availability, as [caller] sees it. */
private suspend fun Catalog.viewPage(
    caller: Caller,
    filter: ProductFilter,
    request: PageRequest,
): Page<ProductView> {
    val visible = filter.copy(includeWithdrawn = filter.includeWithdrawn && caller.isOperator)
    val page = products.page(visible, request)
    val available = availability(page.items)
    return page.map { ProductView(it, available[it.id] ?: 0, caller.isOperator) }
}

/**
 * `listProducts` without `q`: active products ordered by name, paged, optionally of one category and its
 * descendants; withdrawn products are listed only for operators asking for them.
 */
class ListProducts(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        categoryId: CategoryId?,
        includeWithdrawn: Boolean,
        request: PageRequest,
    ): Page<ProductView> = catalog.viewPage(caller, ProductFilter(categoryId, includeWithdrawn), request)
}

/**
 * `listProducts` with `q`: products whose name or description contains the term, case-insensitively, ranked by
 * relevance (whole name, name prefix, anywhere in the name, description); withdrawn products never for shoppers.
 */
class SearchProducts(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        query: String,
        categoryId: CategoryId?,
        includeWithdrawn: Boolean,
        request: PageRequest,
    ): Either<CatalogError, Page<ProductView>> =
        SearchTerm
            .of(query)
            .mapLeft { it.invalid() }
            .map { term -> catalog.viewPage(caller, ProductFilter(categoryId, includeWithdrawn, term), request) }
}

/** `getProduct`: a withdrawn product is not found, except by operators. */
class GetProduct(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        productId: ProductId,
    ): Either<CatalogError, ProductView> {
        val product = catalog.products.find(productId)?.takeIf { it.visibleTo(caller.isOperator) }
        return if (product == null) {
            CatalogError.ProductNotFound(productId).left()
        } else {
            ProductView(product, catalog.availability(listOf(product)).getValue(product.id), caller.isOperator).right()
        }
    }
}

/** `listCategories`: categories by name, optionally the children of one parent. */
class ListCategories(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        parentId: CategoryId?,
        request: PageRequest,
    ): Page<Category> = catalog.categories.page(parentId, request)
}

/** `getCategory`. */
class GetCategory(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(categoryId: CategoryId): Either<CatalogError, Category> =
        catalog.categories.find(categoryId)?.right() ?: CatalogError.CategoryNotFound(categoryId).left()
}

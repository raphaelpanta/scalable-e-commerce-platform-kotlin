package com.ecommerce.catalog.application.admin

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.application.Catalog
import com.ecommerce.catalog.application.OperatorAction
import com.ecommerce.catalog.application.ProductView
import com.ecommerce.catalog.application.WriteResult
import com.ecommerce.catalog.application.accumulating
import com.ecommerce.catalog.application.concatIssues
import com.ecommerce.catalog.application.invalid
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.ImageId
import com.ecommerce.catalog.domain.ImageRef
import com.ecommerce.catalog.domain.InventoryLevel
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductDetails
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.ProductImage
import com.ecommerce.catalog.domain.Sku
import com.ecommerce.catalog.domain.StockLevel

/** The raw fields of catalog.yaml `ProductUpdate` (and of `ProductCreate` without the stock). */
data class ProductInput(
    val name: String,
    val description: String?,
    val priceMinor: Long,
    val currency: String,
    val categoryId: CategoryId,
)

/** The details of [input] in the platform currency, every broken rule reported. */
private fun Catalog.validDetails(input: ProductInput): Either<NonEmptyList<FieldIssue>, ProductDetails> =
    Either.zipOrAccumulate(
        ::concatIssues,
        ProductDetails
            .of(input.name, input.description, input.priceMinor, input.currency, input.categoryId)
            .mapLeft { it.issues },
        platformCurrency(input.currency).accumulating(),
    ) { details, _ -> details }

/**
 * [details] when their category exists and is active: a product cannot be placed in a withdrawn category or one
 * beneath it (422, data-model section 3.2 "category must be active").
 */
private suspend fun Raise<CatalogError>.inActiveCategory(
    catalog: Catalog,
    details: ProductDetails,
): ProductDetails {
    ensureNotNull(catalog.categories.find(details.categoryId)) { CatalogError.CategoryNotFound(details.categoryId) }
    ensure(details.categoryId !in catalog.hiddenCategories()) {
        FieldIssue("categoryId", "must be an active category").invalid()
    }
    return details
}

/** Stores [updated] over [original]; a lost race is [CatalogError.ConcurrentUpdate]. */
private suspend fun Raise<CatalogError>.save(
    catalog: Catalog,
    original: Product,
    updated: Product,
) {
    if (catalog.products.update(updated, original.version) != WriteResult.WRITTEN) raise(CatalogError.ConcurrentUpdate)
}

private suspend fun Raise<CatalogError>.existing(
    catalog: Catalog,
    productId: ProductId,
): Product = ensureNotNull(catalog.products.find(productId)) { CatalogError.ProductNotFound(productId) }

/** [product] as its operator sees it, with the available quantity. */
private suspend fun Catalog.operatorView(product: Product): ProductView =
    ProductView(product, availability(listOf(product)).getValue(product.id), showQuantity = true)

/**
 * `createProduct` (operator only): a new active product with [initialStock] units, visible to shoppers at once. The
 * SKU is generated from the id unless one is given; the category must be active.
 */
class CreateProduct(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        input: ProductInput,
        initialStock: Int,
        sku: String? = null,
    ): Either<CatalogError, ProductView> =
        either {
            val actor = catalog.authorize(caller, ACTION, null).bind()
            val id = ProductId(catalog.newId())
            val (details, stock, validSku) =
                Either
                    .zipOrAccumulate(
                        ::concatIssues,
                        catalog.validDetails(input),
                        StockLevel.of(initialStock).accumulating(),
                        (sku?.let(Sku::of) ?: Either.Right(Sku.generatedFor(id))).accumulating(),
                    ) { valid, level, code -> Triple(valid, level, code) }
                    .mapLeft(CatalogError::Invalid)
                    .bind()
            val product = Product.create(id, validSku, inActiveCategory(catalog, details), catalog.now())
            catalog
                .auditedChange(actor, ACTION, id.value) {
                    when (catalog.products.insert(product)) {
                        WriteResult.WRITTEN -> catalog.inventory.insert(InventoryLevel(id, stock.value, 0))
                        WriteResult.DUPLICATE -> raise(CatalogError.DuplicateSku(validSku))
                        WriteResult.STALE -> raise(CatalogError.ConcurrentUpdate)
                    }
                }.bind()
            ProductView(product, stock.value, showQuantity = true)
        }

    private companion object {
        val ACTION = OperatorAction.CREATE_PRODUCT
    }
}

/** `updateProduct` (operator only): replaces name, description, price and category; stock is not touched. */
class UpdateProduct(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        productId: ProductId,
        input: ProductInput,
    ): Either<CatalogError, ProductView> =
        either {
            val actor = catalog.authorize(caller, ACTION, productId.value).bind()
            val details = catalog.validDetails(input).mapLeft(CatalogError::Invalid).bind()
            val product = existing(catalog, productId)
            inActiveCategory(catalog, details)
            val updated = product.update(details, catalog.now())
            catalog.auditedChange(actor, ACTION, productId.value) { save(catalog, product, updated) }.bind()
            catalog.operatorView(updated)
        }

    private companion object {
        val ACTION = OperatorAction.UPDATE_PRODUCT
    }
}

/** `withdrawProduct` (operator only): hidden from browsing and carts; existing orders are unaffected. */
class WithdrawProduct(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        productId: ProductId,
    ): Either<CatalogError, ProductView> =
        either {
            val actor = catalog.authorize(caller, ACTION, productId.value).bind()
            val product = existing(catalog, productId)
            val withdrawn = product.withdraw(catalog.now()).bind()
            catalog.auditedChange(actor, ACTION, productId.value) { save(catalog, product, withdrawn) }.bind()
            catalog.operatorView(withdrawn)
        }

    private companion object {
        val ACTION = OperatorAction.WITHDRAW_PRODUCT
    }
}

/** `addProductImage` (operator only): registers an image URL; a primary image demotes the previous one. */
class AddProductImage(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        productId: ProductId,
        url: String,
        altText: String?,
        primary: Boolean,
    ): Either<CatalogError, ProductImage> =
        either {
            val actor = catalog.authorize(caller, ACTION, productId.value).bind()
            val image =
                Either
                    .zipOrAccumulate(ImageRef.of(url), ImageRef.altText(altText)) { ref, alt ->
                        ProductImage(ImageId(catalog.newId()), ref, alt, primary)
                    }.mapLeft(CatalogError::Invalid)
                    .bind()
            val product = existing(catalog, productId)
            val updated = product.addImage(image, catalog.now()).bind()
            catalog.auditedChange(actor, ACTION, productId.value) { save(catalog, product, updated) }.bind()
            updated.images.last()
        }

    private companion object {
        val ACTION = OperatorAction.ADD_PRODUCT_IMAGE
    }
}

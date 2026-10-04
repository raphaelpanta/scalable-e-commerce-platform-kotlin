package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

/** Whether a product is on sale. Withdrawing replaces deletion (data-model section 1). */
enum class SaleState {
    ACTIVE,
    WITHDRAWN,
}

/** One image of a product (metadata only; the binary lives elsewhere). */
data class ProductImage(
    val id: ImageId,
    val ref: ImageRef,
    val altText: String?,
    val primary: Boolean,
)

/** What an operator sets on a product (catalog.yaml `ProductUpdate`): name, description, price and category. */
data class ProductDetails(
    val name: ProductName,
    val description: Description?,
    val price: Money,
    val categoryId: CategoryId,
) {
    companion object {
        /** Validates the raw details, reporting every broken rule. */
        fun of(
            name: String,
            description: String?,
            priceMinor: Long,
            currency: String,
            categoryId: CategoryId,
        ): Either<CatalogError.Invalid, ProductDetails> =
            Either
                .zipOrAccumulate(
                    ProductName.of(name),
                    Description.of(description),
                    Money.price(priceMinor, currency),
                ) { validName, validDescription, price ->
                    ProductDetails(validName, validDescription, price, categoryId)
                }.mapLeft(CatalogError::Invalid)
    }
}

/**
 * A catalogue product (data-model section 3.2): SKU, [details], sale state and up to [MAX_IMAGES] images of which
 * exactly one is primary. Every change moves [updatedAt] and [version] (optimistic locking).
 */
data class Product(
    val id: ProductId,
    val sku: Sku,
    val details: ProductDetails,
    val saleState: SaleState,
    val images: List<ProductImage>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    init {
        require(images.size <= MAX_IMAGES) { "a product holds at most $MAX_IMAGES images" }
        require(images.isEmpty() || images.count(ProductImage::primary) == 1) { "exactly one image is primary" }
    }

    val isActive: Boolean get() = saleState == SaleState.ACTIVE

    /**
     * Operators see every product; shoppers see active products outside the [hiddenCategories] (withdrawn categories
     * and the categories beneath them, [CategoryTree.hidden]).
     */
    fun visibleTo(
        operator: Boolean,
        hiddenCategories: Set<CategoryId> = emptySet(),
    ): Boolean = operator || onSale(hiddenCategories)

    /** True when the product can be browsed, carted and reserved: active, in a category that is not hidden. */
    fun onSale(hiddenCategories: Set<CategoryId>): Boolean = isActive && details.categoryId !in hiddenCategories

    /** The product with new [details]; stock is changed through adjustments only. */
    fun update(
        details: ProductDetails,
        at: Instant,
    ): Product = copy(details = details, updatedAt = at, version = version + 1)

    /** Withdraws the product from sale; withdrawing a withdrawn product is refused. Existing orders are unaffected. */
    fun withdraw(at: Instant): Either<CatalogError, Product> =
        if (isActive) {
            copy(saleState = SaleState.WITHDRAWN, updatedAt = at, version = version + 1).right()
        } else {
            CatalogError.AlreadyWithdrawn(id).left()
        }

    /**
     * Puts a withdrawn product back on sale (data-model section 3.2: withdrawing is reversible by operator). Refused
     * when the product is on sale already, and while its category is among the [hiddenCategories] (withdrawn or
     * beneath a withdrawn one): the category must be reinstated first.
     */
    fun reinstate(
        at: Instant,
        hiddenCategories: Set<CategoryId>,
    ): Either<CatalogError, Product> =
        when {
            isActive -> CatalogError.NotWithdrawn(id).left()
            details.categoryId in hiddenCategories -> CatalogError.CategoryWithdrawn(id, details.categoryId).left()
            else -> copy(saleState = SaleState.ACTIVE, updatedAt = at, version = version + 1).right()
        }

    /**
     * Adds an image; the first image, or one marked [ProductImage.primary], becomes the primary image and demotes the
     * previous one. Refused beyond [MAX_IMAGES].
     */
    fun addImage(
        image: ProductImage,
        at: Instant,
    ): Either<CatalogError, Product> {
        if (images.size >= MAX_IMAGES) return CatalogError.TooManyImages(id).left()
        val primary = image.primary || images.isEmpty()
        val kept = if (primary) images.map { it.copy(primary = false) } else images
        return copy(images = kept + image.copy(primary = primary), updatedAt = at, version = version + 1).right()
    }

    companion object {
        const val MAX_IMAGES: Int = 10

        /** A new active product without images. */
        fun create(
            id: ProductId,
            sku: Sku,
            details: ProductDetails,
            at: Instant,
        ): Product = Product(id, sku, details, SaleState.ACTIVE, emptyList(), at, at, 0)
    }
}

package com.ecommerce.catalog.infrastructure.web

import com.ecommerce.catalog.application.ProductPricing
import com.ecommerce.catalog.application.ProductView
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.Money
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.ProductImage
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.StockAdjustment
import com.ecommerce.catalog.domain.UnavailableLine
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** `{"amountMinor": 1999, "currency": "BRL"}`. */
data class MoneyJson(
    val amountMinor: Long,
    val currency: String,
)

/** catalog.yaml `Availability`: `availableQuantity` only for operators, absent (not null) otherwise. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AvailabilityJson(
    val inStock: Boolean,
    val availableQuantity: Int? = null,
)

/** catalog.yaml `ProductImage`. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProductImageJson(
    val id: UUID,
    val url: String,
    val altText: String?,
    val primary: Boolean,
)

/** catalog.yaml `Product` (plus the additive `sku`). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProductJson(
    val id: UUID,
    val sku: String,
    val name: String,
    val description: String?,
    val price: MoneyJson,
    val categoryId: UUID,
    val status: String,
    val images: List<ProductImageJson>,
    val availability: AvailabilityJson,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** catalog.yaml `ProductPage` and `CategoryPage`. */
data class PageJson<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
)

/** catalog.yaml `Category` (with the additive `status`). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CategoryJson(
    val id: UUID,
    val name: String,
    val description: String?,
    val parentId: UUID?,
    val status: String,
)

/** catalog.yaml `StockAdjustment`: quantities are the available quantity before and after. */
data class StockAdjustmentJson(
    val id: UUID,
    val productId: UUID,
    val delta: Int,
    val reason: String,
    val previousQuantity: Int,
    val newQuantity: Int,
    val adjustedBy: UUID,
    val adjustedAt: Instant,
)

/** catalog.yaml `ProductCreate` / `ProductUpdate` (`initialStock` and the optional `sku` on creation only). */
data class ProductRequest(
    val name: String? = null,
    val description: String? = null,
    val price: MoneyRequest? = null,
    val categoryId: UUID? = null,
    val initialStock: Int? = null,
    val sku: String? = null,
)

/** A money member of a request; fields are nullable so that a missing one is answered with a precise 400. */
data class MoneyRequest(
    val amountMinor: Long? = null,
    val currency: String? = null,
)

/** catalog.yaml `StockAdjustmentRequest`. */
data class StockAdjustmentRequest(
    val delta: Int? = null,
    val reason: String? = null,
)

/** catalog.yaml `ProductImageInput`. */
data class ProductImageRequest(
    val url: String? = null,
    val altText: String? = null,
    val primary: Boolean? = null,
)

/** catalog.yaml `CategoryInput`. */
data class CategoryRequest(
    val name: String? = null,
    val description: String? = null,
    val parentId: UUID? = null,
)

/** catalog-internal.yaml `StockLine`. */
data class StockLineJson(
    val productId: UUID,
    val quantity: Int,
)

/** catalog-internal.yaml `ReserveStockRequest`. */
data class ReserveStockRequest(
    val orderId: UUID? = null,
    val lines: List<StockLineRequest?>? = null,
)

/** One requested line of [ReserveStockRequest]. */
data class StockLineRequest(
    val productId: UUID? = null,
    val quantity: Int? = null,
)

/** catalog-internal.yaml `Reservation`. */
data class ReservationJson(
    val reservationId: UUID,
    val orderId: UUID,
    val state: String,
    val lines: List<StockLineJson>,
    val expiresAt: Instant,
)

/** catalog-internal.yaml `UnavailableLine` (the `unavailableLines` member of an `insufficient-stock` problem). */
data class UnavailableLineJson(
    val productId: UUID,
    val requested: Int,
    val available: Int,
)

/** catalog-internal.yaml `ProductPricing`. */
data class ProductPricingJson(
    val productId: UUID,
    val sku: String,
    val name: String,
    val price: MoneyJson,
    val available: Int,
    val saleState: String,
)

/** catalog-internal.yaml `ProductsPricingRequest`. */
data class ProductsPricingRequest(
    val productIds: List<UUID?>? = null,
)

/** catalog-internal.yaml `ProductsPricing`. */
data class ProductsPricingJson(
    val items: List<ProductPricingJson>,
)

private fun Enum<*>.lower(): String = name.lowercase(Locale.ROOT)

fun Money.toJson(): MoneyJson = MoneyJson(amountMinor, currency)

fun ProductImage.toJson(): ProductImageJson = ProductImageJson(id.value, ref.value, altText, primary)

fun ProductView.toJson(): ProductJson =
    ProductJson(
        id = product.id.value,
        sku = product.sku.value,
        name = product.details.name.value,
        description = product.details.description?.value,
        price = product.details.price.toJson(),
        categoryId = product.details.categoryId.value,
        status = product.saleState.lower(),
        images = product.images.map { it.toJson() },
        availability = AvailabilityJson(inStock, available.takeIf { showQuantity }),
        createdAt = product.createdAt,
        updatedAt = product.updatedAt,
    )

fun <T, R> Page<T>.toJson(transform: (T) -> R): PageJson<R> = PageJson(items.map(transform), page, size, totalItems)

fun Category.toJson(): CategoryJson =
    CategoryJson(id.value, details.name.value, details.description?.value, details.parentId?.value, status.lower())

fun StockAdjustment.toJson(): StockAdjustmentJson =
    StockAdjustmentJson(
        id.value,
        productId.value,
        delta,
        reason.value,
        previousAvailable,
        newAvailable,
        actorId.value,
        at,
    )

fun Reservation.toJson(): ReservationJson =
    ReservationJson(
        id.value,
        orderId.value,
        state.lower(),
        lines.map { StockLineJson(it.productId.value, it.quantity.value) },
        expiresAt,
    )

fun UnavailableLine.toJson(): UnavailableLineJson = UnavailableLineJson(productId.value, requested, available)

fun ProductPricing.toJson(): ProductPricingJson =
    ProductPricingJson(
        product.id.value,
        product.sku.value,
        product.details.name.value,
        product.details.price.toJson(),
        available,
        saleState.lower(),
    )

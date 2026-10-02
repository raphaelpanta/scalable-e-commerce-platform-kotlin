package com.ecommerce.catalog.domain

import java.util.UUID

/** Identifier of a product (data-model section 2: one typed id per concept, so ids cannot be mixed). */
@JvmInline
value class ProductId(
    val value: UUID,
)

/** Identifier of a category. */
@JvmInline
value class CategoryId(
    val value: UUID,
)

/** Identifier of a stock reservation; equals the `aggregateId` of the stock events. */
@JvmInline
value class ReservationId(
    val value: UUID,
)

/** Identifier of an order (owned by the order service; the reservation key). */
@JvmInline
value class OrderId(
    val value: UUID,
)

/** Identifier of a product image. */
@JvmInline
value class ImageId(
    val value: UUID,
)

/** Identifier of a stock adjustment record. */
@JvmInline
value class AdjustmentId(
    val value: UUID,
)

/** Identifier of an account (owned by identity): the operator of an adjustment, the caller of a refused attempt. */
@JvmInline
value class AccountId(
    val value: UUID,
)

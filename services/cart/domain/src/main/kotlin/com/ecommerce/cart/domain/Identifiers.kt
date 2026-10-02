package com.ecommerce.cart.domain

import java.util.UUID

/** Identifier of a cart (data-model section 2: one typed id per concept, so ids cannot be mixed). */
@JvmInline
value class CartId(
    val value: UUID,
)

/** Identifier of a cart line; equals the `id` of the line in the public cart and `lineId` in the internal one. */
@JvmInline
value class LineId(
    val value: UUID,
)

/** Identifier of a shopper's account (the `sub` claim of the access token, owned by identity). */
@JvmInline
value class AccountId(
    val value: UUID,
)

/** Identifier of a catalogue product (owned by catalog; the cart holds the id only). */
@JvmInline
value class ProductId(
    val value: UUID,
)

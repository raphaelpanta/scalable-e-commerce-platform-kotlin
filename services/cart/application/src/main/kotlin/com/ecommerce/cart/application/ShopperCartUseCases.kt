package com.ecommerce.cart.application

import arrow.core.Either
import arrow.core.raise.either
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartOwner
import com.ecommerce.cart.domain.LineId
import com.ecommerce.cart.domain.PricedCart
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.Quantity

/** The owner of a cart created for [identity]: its account, or the holder of the [issued] token. */
internal fun ownerOf(
    identity: CartIdentity,
    issued: IssuedToken?,
): CartOwner =
    if (identity is CartIdentity.Account) {
        CartOwner.Account(identity.accountId)
    } else {
        CartOwner.Anonymous(issued?.hash.orEmpty())
    }

/** `getCart`: the caller's cart at current prices; an empty cart (nothing stored) when the caller has none yet. */
class GetCart(
    private val store: CartStore,
) {
    suspend operator fun invoke(identity: CartIdentity): Either<CartError, PricedCart> =
        either {
            val cart = store.load(identity).bind() ?: Cart.new(Cart.NO_CART, ownerOf(identity, null), store.now())
            store.price(cart)
        }
}

/** The cart after `addCartLine` and the token issued when the call started a new anonymous cart. */
data class LineAdded(
    val cart: PricedCart,
    val issuedToken: IssuedToken?,
)

/**
 * `addCartLine`: adds [quantity] units of a product the catalogue knows ([CartError.ProductNotFound] otherwise) to
 * the caller's cart, creating it on the first write; an unidentified caller gets a new anonymous cart and token.
 */
class AddLine(
    private val store: CartStore,
    private val tokens: AnonymousTokens,
) {
    suspend operator fun invoke(
        identity: CartIdentity,
        productId: ProductId,
        quantity: Int,
    ): Either<CartError, LineAdded> =
        either {
            val units = Quantity.of(quantity).bind()
            val existing = store.load(identity).bind()
            val quote = store.quote(productId) ?: raise(CartError.ProductNotFound(productId))
            val issued = if (existing == null && identity == CartIdentity.Unidentified) tokens.issue() else null
            val cart = existing ?: Cart.new(store.newCartId(), ownerOf(identity, issued), store.now())
            val updated = cart.add(quote, units, store.newLineId(), store.now()).bind()
            store.persist(existing, updated).bind()
            LineAdded(store.price(updated), issued)
        }
}

/** `updateCartLineQuantity`: sets a line's quantity within the available stock; 0 removes the line. */
class UpdateLineQuantity(
    private val store: CartStore,
) {
    suspend operator fun invoke(
        identity: CartIdentity,
        lineId: LineId,
        quantity: Int,
    ): Either<CartError, PricedCart> =
        either {
            val cart = store.load(identity).bind() ?: raise(CartError.LineNotFound(lineId))
            val line = cart.line(lineId) ?: raise(CartError.LineNotFound(lineId))
            val updated =
                if (quantity == 0) {
                    cart.remove(lineId, store.now()).bind()
                } else {
                    val quote = store.quote(line.productId) ?: raise(CartError.ProductUnavailable(line.productId))
                    cart.changeQuantity(lineId, quantity, quote, store.now()).bind()
                }
            store.persist(cart, updated).bind()
            store.price(updated)
        }
}

/** `removeCartLine`: removes one line of the caller's cart. */
class RemoveLine(
    private val store: CartStore,
) {
    suspend operator fun invoke(
        identity: CartIdentity,
        lineId: LineId,
    ): Either<CartError, PricedCart> =
        either {
            val cart = store.load(identity).bind() ?: raise(CartError.LineNotFound(lineId))
            val updated = cart.remove(lineId, store.now()).bind()
            store.persist(cart, updated).bind()
            store.price(updated)
        }
}

/** `clearCart`: removes every line of the caller's cart; idempotent (nothing to do without a cart or lines). */
class ClearCart(
    private val store: CartStore,
) {
    suspend operator fun invoke(identity: CartIdentity): Either<CartError, Unit> =
        either {
            val cart = store.load(identity).bind()
            if (cart != null && cart.lines.isNotEmpty()) store.persist(cart, cart.clear(store.now())).bind()
        }
}

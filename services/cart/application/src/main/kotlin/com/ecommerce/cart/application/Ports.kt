package com.ecommerce.cart.application

import arrow.core.Either
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.CappedLine
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartId
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.ProductQuote
import java.time.Instant

/** Who is asking for a cart: a signed-in account, the holder of an anonymous token, or a caller with neither. */
sealed interface CartIdentity {
    /** A bearer token was presented: the account cart, whatever `X-Cart-Token` says. */
    data class Account(
        val accountId: AccountId,
    ) : CartIdentity

    /** An `X-Cart-Token` was presented; only its hash is ever compared or stored. */
    data class Anonymous(
        val tokenHash: String,
    ) : CartIdentity

    /** Neither: an empty cart to read, a new anonymous cart on the first write. */
    data object Unidentified : CartIdentity
}

/** Outbound port: the carts of this service (lines included), with optimistic locking on [Cart.version]. */
interface CartRepository {
    suspend fun findByAccount(accountId: AccountId): Cart?

    suspend fun findByTokenHash(tokenHash: String): Cart?

    /** Stores a new cart; false when its owner already has one (a concurrent first write). */
    suspend fun insert(cart: Cart): Boolean

    /** Replaces a stored cart whose version is still [expectedVersion]; false when it moved on (or is gone). */
    suspend fun update(
        cart: Cart,
        expectedVersion: Long,
    ): Boolean

    /** Deletes [cart] if it is still at its version; false when it moved on or was already deleted. */
    suspend fun delete(cart: Cart): Boolean

    /** Deletes the cart of [accountId], if any; true when one was deleted. */
    suspend fun deleteByAccount(accountId: AccountId): Boolean

    /** Deletes the anonymous carts last changed before [cutoff]; returns how many. */
    suspend fun deleteAnonymousIdleSince(cutoff: Instant): Int
}

/** Outbound port: live price, name, sku and availability from the catalogue (catalog-internal.yaml pricing). */
interface CatalogPricing {
    /** The quote of [productId], or null when the catalogue does not know it. */
    suspend fun quote(productId: ProductId): ProductQuote?

    /** The quotes of the known products among [productIds] (unknown ones are absent). */
    suspend fun quotes(productIds: Collection<ProductId>): Map<ProductId, ProductQuote>
}

/** Outbound port: runs a block in one database transaction, rolled back when the block returns an error. */
interface Transactions {
    suspend fun <T> inTransaction(block: suspend () -> Either<CartError, T>): Either<CartError, T>
}

/** `CartMerged` (events.yaml `CartMergedPayload`): anonymous carts merged into the account cart [cartId]. */
data class CartMerged(
    val accountId: AccountId,
    val cartId: CartId,
    val mergedCartCount: Int,
    val lineCount: Int,
    val cappedLines: List<CappedLine>,
)

/** Outbound port: the events this service publishes (through the transactional outbox, inside the transaction). */
fun interface CartEvents {
    suspend fun cartMerged(event: CartMerged)
}

/** A freshly issued anonymous cart token: the [value] for the client, the [hash] for storage. Never logged. */
class IssuedToken(
    val value: String,
    val hash: String,
) {
    override fun toString(): String = "IssuedToken(****)"
}

/** Outbound port: issues opaque anonymous cart tokens (256-bit random). */
fun interface AnonymousTokens {
    fun issue(): IssuedToken
}

package com.ecommerce.cart.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartId
import com.ecommerce.cart.domain.LineId
import com.ecommerce.cart.domain.PricedCart
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.ProductQuote
import com.ecommerce.cart.domain.priced
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What every use case needs: the repository and the catalogue behind small helpers, the platform [currency], the
 * [clock] (truncated to microseconds, the precision PostgreSQL keeps) and the id source.
 */
class CartStore(
    val repository: CartRepository,
    private val pricing: CatalogPricing,
    val currency: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ids: () -> UUID = UUID::randomUUID,
) {
    fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    fun newCartId(): CartId = CartId(ids())

    fun newLineId(): LineId = LineId(ids())

    /** The stored cart of [identity]: null when there is none yet, [CartError.CartNotFound] for an unknown token. */
    suspend fun load(identity: CartIdentity): Either<CartError, Cart?> =
        when (identity) {
            is CartIdentity.Account -> {
                repository.findByAccount(identity.accountId).right()
            }

            is CartIdentity.Anonymous -> {
                repository.findByTokenHash(identity.tokenHash)?.right() ?: CartError.CartNotFound.left()
            }

            CartIdentity.Unidentified -> {
                null.right()
            }
        }

    /** Stores [updated]: inserted when there was no [original], else updated if still at the original's version. */
    suspend fun persist(
        original: Cart?,
        updated: Cart,
    ): Either<CartError, Cart> {
        val stored = if (original == null) repository.insert(updated) else repository.update(updated, original.version)
        return if (stored) updated.right() else CartError.ConcurrentUpdate.left()
    }

    suspend fun quote(productId: ProductId): ProductQuote? = pricing.quote(productId)

    suspend fun quotes(productIds: Collection<ProductId>): Map<ProductId, ProductQuote> = pricing.quotes(productIds)

    /** [cart] priced with the live catalogue prices of its products. */
    suspend fun price(cart: Cart): PricedCart =
        cart.priced(if (cart.lines.isEmpty()) emptyMap() else pricing.quotes(cart.productIds), currency)
}

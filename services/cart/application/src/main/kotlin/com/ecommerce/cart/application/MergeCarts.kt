package com.ecommerce.cart.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.CappedLine
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartOwner
import com.ecommerce.cart.domain.PricedCart
import com.ecommerce.cart.domain.mergeFrom

/** The account cart after `mergeCart` and the lines whose summed quantity was capped. */
data class MergeResult(
    val cart: PricedCart,
    val cappedLines: List<CappedLine>,
)

/**
 * `mergeCart` (FR-009): merges the anonymous cart of [tokenHash] into the account cart (created when the account has
 * none), consumes the anonymous cart and publishes `CartMerged`, all in one transaction. An unknown or already merged
 * token is [CartError.CartNotFound]; losing a race against a concurrent merge of the same token (or a concurrent
 * change of the account cart) is [CartError.ConcurrentUpdate] and changes nothing.
 */
class MergeCarts(
    private val store: CartStore,
    private val transactions: Transactions,
    private val events: CartEvents,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        tokenHash: String,
    ): Either<CartError, MergeResult> =
        either {
            val anonymous = store.repository.findByTokenHash(tokenHash) ?: raise(CartError.CartNotFound)
            val existing = store.repository.findByAccount(accountId)
            val target = existing ?: Cart.new(store.newCartId(), CartOwner.Account(accountId), store.now())
            val outcome = target.mergeFrom(anonymous, store.quotes(anonymous.productIds), store::newLineId, store.now())
            transactions
                .inTransaction {
                    either {
                        ensure(store.repository.delete(anonymous)) { CartError.ConcurrentUpdate }
                        store.persist(existing, outcome.cart).bind()
                        val merged = outcome.cart
                        events.cartMerged(CartMerged(accountId, merged.id, 1, merged.lines.size, outcome.cappedLines))
                    }
                }.bind()
            MergeResult(store.price(outcome.cart), outcome.cappedLines)
        }
}

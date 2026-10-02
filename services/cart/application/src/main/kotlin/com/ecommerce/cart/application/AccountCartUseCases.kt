package com.ecommerce.cart.application

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.Money
import com.ecommerce.cart.domain.OrderedItem
import com.ecommerce.cart.domain.PricedCart
import java.time.Duration

/** Attempts of a background change that lost an optimistic-locking race before it gives up. */
internal const val CONFLICT_ATTEMPTS: Int = 3

/** Re-reads the account cart and applies [change] until it is stored, at most [CONFLICT_ATTEMPTS] times. */
internal suspend fun CartStore.changeAccountCart(
    accountId: AccountId,
    change: (Cart) -> Cart,
): Either<CartError, Unit> {
    var outcome: Either<CartError, Unit> = CartError.ConcurrentUpdate.left()
    var attempt = 0
    while (attempt < CONFLICT_ATTEMPTS && outcome.isLeft()) {
        attempt++
        val cart = repository.findByAccount(accountId) ?: return Unit.right()
        val changed = change(cart)
        outcome = if (changed === cart) Unit.right() else persist(cart, changed).map { }
    }
    return outcome
}

/** The account cart as order reads it: the cart at current prices and the informational total at add prices. */
data class AccountCart(
    val priced: PricedCart,
    val totalAtAdd: Money,
)

/** `getCartByAccount` (internal): the account's cart with its current revision, or null when it has none. */
class GetAccountCart(
    private val store: CartStore,
) {
    suspend operator fun invoke(accountId: AccountId): AccountCart? =
        store.repository.findByAccount(accountId)?.let { cart ->
            AccountCart(store.price(cart), cart.totalAtAdd(store.currency))
        }
}

/** `clearCartByAccount` (internal, after payment approval): removes every line; idempotent. */
class ClearAccountCart(
    private val store: CartStore,
) {
    suspend operator fun invoke(accountId: AccountId): Either<CartError, Unit> =
        store.changeAccountCart(accountId) { cart -> if (cart.lines.isEmpty()) cart else cart.clear(store.now()) }
}

/** `OrderPaid` consumer: takes the ordered quantities out of the account's cart (same end state as the clear). */
class RemoveOrderedLines(
    private val store: CartStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        ordered: List<OrderedItem>,
    ): Either<CartError, Unit> = store.changeAccountCart(accountId) { cart -> cart.removeOrdered(ordered, store.now()) }
}

/** `AccountDeleted` consumer: discards the account's cart (data-model section 1, deletion). */
class DeleteAccountCart(
    private val store: CartStore,
) {
    suspend operator fun invoke(accountId: AccountId): Boolean = store.repository.deleteByAccount(accountId)
}

/** Deletes the anonymous carts idle for longer than [idleTimeout] (data-model section 5: 30 days). */
class PurgeIdleCarts(
    private val store: CartStore,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
) {
    suspend operator fun invoke(): Int = store.repository.deleteAnonymousIdleSince(store.now().minus(idleTimeout))

    companion object {
        val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofDays(30)
    }
}

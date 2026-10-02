package com.ecommerce.cart.application

import arrow.core.Either
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartId
import com.ecommerce.cart.domain.CartLine
import com.ecommerce.cart.domain.CartOwner
import com.ecommerce.cart.domain.LineId
import com.ecommerce.cart.domain.Money
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.ProductQuote
import com.ecommerce.cart.domain.Quantity
import com.ecommerce.cart.domain.SaleState
import io.kotest.assertions.fail
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

const val BRL = "BRL"
val NOW: Instant = Instant.parse("2026-10-02T10:00:00.123456Z")

fun money(amountMinor: Long): Money = Money(amountMinor, BRL)

fun quantity(value: Int): Quantity = Quantity.of(value).getOrNull() ?: fail("$value is not a quantity")

fun productId(): ProductId = ProductId(UUID.randomUUID())

fun accountId(): AccountId = AccountId(UUID.randomUUID())

fun quote(
    productId: ProductId = productId(),
    price: Long = 1000,
    available: Int = 50,
    saleState: SaleState = SaleState.ACTIVE,
): ProductQuote = ProductQuote(productId, "SKU-1", "Product", money(price), available, saleState)

fun line(
    productId: ProductId = productId(),
    quantity: Int = 1,
    price: Long = 1000,
): CartLine = CartLine(LineId(UUID.randomUUID()), productId, "SKU-1", "Product", quantity(quantity), money(price), NOW)

fun accountCart(
    accountId: AccountId,
    vararg lines: CartLine,
): Cart = Cart(CartId(UUID.randomUUID()), CartOwner.Account(accountId), lines.toList(), 1, NOW)

fun anonymousCart(
    tokenHash: String,
    vararg lines: CartLine,
): Cart = Cart(CartId(UUID.randomUUID()), CartOwner.Anonymous(tokenHash), lines.toList(), 1, NOW)

fun <T> Either<CartError, T>.value(): T = fold({ fail("expected a value but was $it") }, { it })

fun <T> Either<CartError, T>.error(): CartError = fold({ it }, { fail("expected an error but was $it") })

/** Carts in memory, with the optimistic-locking rules of the real adapter and switches to lose races. */
class InMemoryCarts : CartRepository {
    val carts = linkedMapOf<CartId, Cart>()
    var losingInserts = 0
    var losingUpdates = 0
    var losingDeletes = 0
    var updates = 0
    var purgeCutoff: Instant? = null

    fun store(vararg stored: Cart) = stored.forEach { carts[it.id] = it }

    fun ofAccount(accountId: AccountId): Cart? = carts.values.firstOrNull { it.owner == CartOwner.Account(accountId) }

    override suspend fun findByAccount(accountId: AccountId): Cart? {
        yield()
        return ofAccount(accountId)
    }

    override suspend fun findByTokenHash(tokenHash: String): Cart? {
        yield()
        return carts.values.firstOrNull { it.owner == CartOwner.Anonymous(tokenHash) }
    }

    override suspend fun insert(cart: Cart): Boolean {
        yield()
        if (losingInserts > 0 || carts.values.any { it.owner == cart.owner }) {
            losingInserts--
            return false
        }
        carts[cart.id] = cart
        return true
    }

    override suspend fun update(
        cart: Cart,
        expectedVersion: Long,
    ): Boolean {
        yield()
        updates++
        if (losingUpdates > 0 || carts[cart.id]?.version != expectedVersion) {
            losingUpdates--
            return false
        }
        carts[cart.id] = cart
        return true
    }

    override suspend fun delete(cart: Cart): Boolean {
        yield()
        if (losingDeletes > 0 || carts[cart.id]?.version != cart.version) {
            losingDeletes--
            return false
        }
        carts.remove(cart.id)
        return true
    }

    override suspend fun deleteByAccount(accountId: AccountId): Boolean {
        yield()
        return ofAccount(accountId)?.let { carts.remove(it.id) } != null
    }

    override suspend fun deleteAnonymousIdleSince(cutoff: Instant): Int {
        yield()
        purgeCutoff = cutoff
        val idle = carts.values.filter { it.owner is CartOwner.Anonymous && it.updatedAt < cutoff }
        idle.forEach { carts.remove(it.id) }
        return idle.size
    }
}

/** A catalogue answering from [quotes]; records the products asked for. */
class FakeCatalog(
    vararg known: ProductQuote,
) : CatalogPricing {
    val quotes = known.associateBy { it.productId }.toMutableMap()
    val asked = mutableListOf<Collection<ProductId>>()

    override suspend fun quote(productId: ProductId): ProductQuote? {
        yield()
        asked += listOf(productId)
        return quotes[productId]
    }

    override suspend fun quotes(productIds: Collection<ProductId>): Map<ProductId, ProductQuote> {
        yield()
        asked += productIds
        return quotes.filterKeys { it in productIds }
    }
}

/** Runs the block and restores [carts] when it returns an error, like a rolled-back transaction. */
class FakeTransactions(
    private val carts: InMemoryCarts,
) : Transactions {
    var rollbacks = 0

    override suspend fun <T> inTransaction(block: suspend () -> Either<CartError, T>): Either<CartError, T> {
        yield()
        val before = LinkedHashMap(carts.carts)
        return block().onLeft {
            rollbacks++
            carts.carts.clear()
            carts.carts.putAll(before)
        }
    }
}

class RecordedEvents : CartEvents {
    val merged = mutableListOf<CartMerged>()

    override suspend fun cartMerged(event: CartMerged) {
        yield()
        merged += event
    }
}

/** Everything a use case needs, in memory, at a fixed clock. */
class Harness(
    vararg known: ProductQuote,
) {
    val carts = InMemoryCarts()
    val catalog = FakeCatalog(*known)
    val transactions = FakeTransactions(carts)
    val events = RecordedEvents()
    val issued = mutableListOf<IssuedToken>()
    val tokens = AnonymousTokens { IssuedToken("token-${issued.size}", "hash-${issued.size}").also { issued += it } }
    val store = CartStore(carts, catalog, BRL, Clock.fixed(NOW, ZoneOffset.UTC))
}

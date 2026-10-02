package com.ecommerce.cart.domain

import arrow.core.Either
import io.kotest.assertions.fail
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import java.time.Instant
import java.util.UUID

const val BRL = "BRL"
val T0: Instant = Instant.parse("2026-10-02T10:00:00Z")
val LATER: Instant = Instant.parse("2026-10-02T11:00:00Z")

fun money(amountMinor: Long): Money = Money(amountMinor, BRL)

fun quantity(value: Int): Quantity = Quantity.of(value).getOrNull() ?: fail("$value is not a quantity")

fun newLineId(): LineId = LineId(UUID.randomUUID())

fun productId(): ProductId = ProductId(UUID.randomUUID())

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
): CartLine = CartLine(newLineId(), productId, "SKU-1", "Product", quantity(quantity), money(price), T0)

fun cartOf(
    lines: List<CartLine> = emptyList(),
    version: Long = 3,
): Cart = Cart(CartId(UUID.randomUUID()), CartOwner.Account(AccountId(UUID.randomUUID())), lines, version, T0)

fun <T> Either<CartError, T>.value(): T = fold({ fail("expected a value but was $it") }, { it })

fun <T> Either<CartError, T>.error(): CartError = fold({ it }, { fail("expected an error but was $it") })

/** The quotes of [cart]'s products at their price at add (no price change), with plenty of stock. */
fun quotesAtAdd(cart: Cart): Map<ProductId, ProductQuote> =
    cart.lines.associate { held -> held.productId to quote(held.productId, held.priceAtAdd.amountMinor, Quantity.MAX) }

val arbPrice: Arb<Long> = Arb.long(0L..1_000_000L)

val arbQuantity: Arb<Int> = Arb.int(Quantity.MIN..Quantity.MAX)

val arbLine: Arb<CartLine> =
    arbitrary {
        CartLine(
            newLineId(),
            productId(),
            "SKU-1",
            "Product",
            quantity(arbQuantity.bind()),
            money(arbPrice.bind()),
            T0,
        )
    }

/** Carts of 0 to 10 lines, one per product. */
val arbCart: Arb<Cart> =
    arbitrary {
        val size = Arb.int(0..10).bind()
        val lines = List(size) { arbLine.bind() }.distinctBy { line -> line.productId }
        Cart(CartId(UUID.randomUUID()), CartOwner.Anonymous("hash"), lines, Arb.long(0L..1000L).bind(), T0)
    }

package com.ecommerce.cart.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

private const val ABOVE_MAX = 100

class ValueObjectsSpec :
    FunSpec({
        test("a quantity is 1..99") {
            checkAll(Arb.int()) { value ->
                val result = Quantity.of(value)
                if (value in 1..Quantity.MAX) {
                    result.value().value shouldBe value
                } else {
                    result.error() shouldBe CartError.InvalidQuantity(value)
                }
            }
            Quantity.of(0).error() shouldBe CartError.InvalidQuantity(0)
            Quantity.of(ABOVE_MAX).error() shouldBe CartError.InvalidQuantity(ABOVE_MAX)
            Quantity.of(1).value().value shouldBe 1
            Quantity.of(Quantity.MAX).value().value shouldBe Quantity.MAX
        }

        test("money adds and multiplies within one currency") {
            checkAll(arbPrice, arbPrice, arbQuantity) { a, b, units ->
                money(a) + money(b) shouldBe money(a + b)
                money(a) * quantity(units) shouldBe money(a * units)
                Money.sum(listOf(money(a), money(b)), BRL) shouldBe money(a + b)
            }
            Money.sum(emptyList(), "USD") shouldBe Money(0, "USD")
            Money.zero(BRL) shouldBe money(0)
        }

        test("money refuses negative amounts, unknown currency codes, mixed currencies and overflow") {
            checkAll(Arb.long(Long.MIN_VALUE..-1L)) { negative ->
                shouldThrow<IllegalArgumentException> { Money(negative, BRL) }
            }
            Money(0, BRL).amountMinor shouldBe 0
            listOf("brl", "BR", "BRLX", "B1L", "").forEach { code ->
                shouldThrow<IllegalArgumentException> { Money(1, code) }
            }
            shouldThrow<IllegalArgumentException> { money(1) + Money(1, "USD") }
            shouldThrow<ArithmeticException> { money(Long.MAX_VALUE) + money(1) }
            shouldThrow<ArithmeticException> { money(Long.MAX_VALUE) * quantity(2) }
        }

        test("a quote is sellable up to its stock, at most 99, and never when withdrawn") {
            checkAll(Arb.int(-10..200)) { available ->
                quote(available = available).sellable() shouldBe available.coerceIn(0, Quantity.MAX)
                quote(available = available, saleState = SaleState.WITHDRAWN).sellable() shouldBe 0
            }
        }

        test("errors, capped lines, lines, owners and quotes expose what they carry") {
            val stock = CartError.InsufficientStock(productId(), 5, 3)
            stock.requested shouldBe 5
            stock.available shouldBe 3
            CartError.InvalidQuantity(7).requested shouldBe 7
            val capped = CappedLine(productId(), 4, 3)
            capped.requestedQuantity shouldBe 4
            capped.appliedQuantity shouldBe 3
            val line = line()
            line.sku shouldBe "SKU-1"
            line.name shouldBe "Product"
            CartOwner.Anonymous("hash").tokenHash shouldBe "hash"
            quote(available = 7).available shouldBe 7
            money(1).currency shouldBe BRL
        }

        test("a quote allows a sellable quantity only") {
            checkAll(Arb.int(0..120), Arb.int(-5..120)) { available, wanted ->
                val quote = quote(available = available)
                val result = quote.allow(wanted)
                when {
                    wanted > available -> {
                        result.error() shouldBe
                            CartError.InsufficientStock(quote.productId, wanted, available)
                    }

                    wanted !in 1..Quantity.MAX -> {
                        result.error() shouldBe CartError.InvalidQuantity(wanted)
                    }

                    else -> {
                        result.value().value shouldBe wanted
                    }
                }
            }
        }
    })

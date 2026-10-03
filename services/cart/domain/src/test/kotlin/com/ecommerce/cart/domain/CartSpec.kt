package com.ecommerce.cart.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

private const val SOME_PRICE = 123L
private const val PLENTY = 500
private const val SIXTY = 60
private const val FORTY = 40
private const val ABOVE_MAX = 100
private const val SMALL_CART = 5

class CartSpec :
    FunSpec({
        fun revisionOf(cart: Cart): CartRevision = cart.priced(quotesAtAdd(cart), BRL).revision

        fun fullCart(): Cart = cartOf(List(Cart.MAX_LINES) { line() })

        context("add") {
            test("a new product becomes a line with the quoted snapshots, one version more") {
                checkAll(arbCart, arbQuantity, arbPrice) { cart, requested, price ->
                    val quote = quote(price = price, available = Quantity.MAX)
                    val lineId = newLineId()

                    val added = cart.add(quote, quantity(requested), lineId, LATER).value()

                    added.lines shouldHaveSize cart.lines.size + 1
                    added.lines.last() shouldBe
                        CartLine(lineId, quote.productId, "SKU-1", "Product", quantity(requested), money(price), LATER)
                    added.lines.dropLast(1) shouldBe cart.lines
                    added.version shouldBe cart.version + 1
                    added.updatedAt shouldBe LATER
                    added.id shouldBe cart.id
                    added.owner shouldBe cart.owner
                    revisionOf(added) shouldNotBe revisionOf(cart)
                }
            }

            test("a product already in the cart has its quantity summed and its snapshots refreshed") {
                checkAll(Arb.int(1..50), Arb.int(1..49), arbPrice) { held, more, price ->
                    val other = line()
                    val existing = line(quantity = held, price = SOME_PRICE)
                    val cart = cartOf(listOf(other, existing))
                    val quote =
                        ProductQuote(
                            existing.productId,
                            "NEW-SKU",
                            "New name",
                            money(price),
                            held + more,
                            SaleState.ACTIVE,
                        )

                    val added = cart.add(quote, quantity(more), newLineId(), LATER).value()

                    added.lines shouldHaveSize 2
                    added.lines.first() shouldBe other
                    added.lines.last() shouldBe
                        existing.copy(
                            quantity = quantity(held + more),
                            sku = "NEW-SKU",
                            name = "New name",
                            priceAtAdd = money(price),
                        )
                    added.version shouldBe cart.version + 1
                }
            }

            test("a resulting quantity above the available stock is refused naming the available quantity") {
                checkAll(Arb.int(0..98), Arb.int(1..20)) { available, held ->
                    val existing = line(quantity = held)
                    val requested = (available + 1 - held).coerceAtLeast(1)

                    cartOf(listOf(existing))
                        .add(quote(existing.productId, available = available), quantity(requested), newLineId(), LATER)
                        .error() shouldBe CartError.InsufficientStock(existing.productId, held + requested, available)
                }
            }

            test("exactly the available stock is accepted") {
                checkAll(arbQuantity) { available ->
                    cartOf()
                        .add(quote(available = available), quantity(available), newLineId(), LATER)
                        .value()
                        .lines
                        .single()
                        .quantity shouldBe quantity(available)
                }
            }

            test("more than 99 units of one product are refused even with stock") {
                val existing = line(quantity = SIXTY)

                cartOf(listOf(existing))
                    .add(quote(existing.productId, available = PLENTY), quantity(FORTY), newLineId(), LATER)
                    .error() shouldBe CartError.InvalidQuantity(ABOVE_MAX)
            }

            test("a withdrawn product is refused") {
                val withdrawn = quote(saleState = SaleState.WITHDRAWN)

                cartOf().add(withdrawn, quantity(1), newLineId(), LATER).error() shouldBe
                    CartError.ProductUnavailable(withdrawn.productId)
            }

            test("a 101st product is refused but a product already in a full cart can still be added") {
                val full = fullCart()

                full.add(quote(), quantity(1), newLineId(), LATER).error() shouldBe CartError.TooManyLines
                val held = full.lines.first()
                full.add(quote(held.productId), quantity(1), newLineId(), LATER).value().lines shouldHaveSize
                    Cart.MAX_LINES
                cartOf(full.lines.drop(1)).add(quote(), quantity(1), newLineId(), LATER).value().lines shouldHaveSize
                    Cart.MAX_LINES
            }
        }

        context("change quantity") {
            test("sets the quantity of the line within the available stock, one version more") {
                checkAll(arbCart, arbQuantity) { cart, wanted ->
                    if (cart.lines.isNotEmpty()) {
                        val target = cart.lines.last()

                        val changed =
                            cart
                                .changeQuantity(
                                    target.id,
                                    wanted,
                                    quote(target.productId, available = wanted),
                                    LATER,
                                ).value()

                        changed.lines.last() shouldBe target.copy(quantity = quantity(wanted))
                        changed.lines.dropLast(1) shouldBe cart.lines.dropLast(1)
                        changed.version shouldBe cart.version + 1
                        changed.updatedAt shouldBe LATER
                    }
                }
            }

            test("a quantity above the available stock is refused") {
                checkAll(Arb.int(1..98)) { available ->
                    val target = line()

                    cartOf(listOf(target))
                        .changeQuantity(target.id, available + 1, quote(target.productId, available = available), LATER)
                        .error() shouldBe CartError.InsufficientStock(target.productId, available + 1, available)
                }
            }

            test("an unknown line, a quantity outside 1..99 and a withdrawn product are refused") {
                val target = line()
                val cart = cartOf(listOf(target))
                val unknown = newLineId()

                cart.changeQuantity(unknown, 1, quote(target.productId), LATER).error() shouldBe
                    CartError.LineNotFound(unknown)
                cart
                    .changeQuantity(
                        target.id,
                        ABOVE_MAX,
                        quote(target.productId, available = PLENTY),
                        LATER,
                    ).error() shouldBe
                    CartError.InvalidQuantity(ABOVE_MAX)
                cart
                    .changeQuantity(
                        target.id,
                        1,
                        quote(target.productId, saleState = SaleState.WITHDRAWN),
                        LATER,
                    ).error() shouldBe
                    CartError.ProductUnavailable(target.productId)
            }
        }

        context("remove and clear") {
            test("removing a line keeps the others, one version more") {
                checkAll(arbCart) { cart ->
                    cart.lines.forEach { target ->
                        val removed = cart.remove(target.id, LATER).value()

                        removed.lines shouldBe cart.lines.filterNot { it.id == target.id }
                        removed.version shouldBe cart.version + 1
                        removed.updatedAt shouldBe LATER
                        revisionOf(removed) shouldNotBe revisionOf(cart)
                    }
                }
            }

            test("removing an unknown line is refused") {
                val unknown = newLineId()

                cartOf(listOf(line())).remove(unknown, LATER).error() shouldBe CartError.LineNotFound(unknown)
            }

            test("clearing removes every line, one version more, and changes the revision even when empty") {
                checkAll(arbCart) { cart ->
                    val cleared = cart.clear(LATER)

                    cleared.lines.shouldBeEmpty()
                    cleared.version shouldBe cart.version + 1
                    cleared.updatedAt shouldBe LATER
                    revisionOf(cleared) shouldNotBe revisionOf(cart)
                }
            }
        }

        context("totals and revision") {
            test("the total is the sum of quantity x current price and the internal total uses the price at add") {
                checkAll(arbCart, arbPrice) { cart, current ->
                    val quotes = cart.lines.associate { it.productId to quote(it.productId, price = current) }

                    val priced = cart.priced(quotes, BRL)

                    priced.total shouldBe money(cart.lines.sumOf { it.quantity.value * current })
                    priced.lines.map { it.lineTotal } shouldBe cart.lines.map { money(it.quantity.value * current) }
                    cart.totalAtAdd(BRL) shouldBe
                        money(cart.lines.sumOf { it.quantity.value * it.priceAtAdd.amountMinor })
                }
            }

            test("a line is flagged exactly when its current price differs from its price at add") {
                checkAll(arbCart, arbPrice) { cart, current ->
                    val quotes = cart.lines.associate { it.productId to quote(it.productId, price = current) }

                    cart.priced(quotes, BRL).lines.forEach { priced ->
                        priced.currentPrice shouldBe money(current)
                        priced.priceChanged shouldBe (priced.line.priceAtAdd != money(current))
                    }
                }
            }

            test("a product the catalogue no longer knows keeps its price at add") {
                checkAll(arbCart) { cart ->
                    cart.priced(emptyMap(), BRL).lines.forEach { priced ->
                        priced.currentPrice shouldBe priced.line.priceAtAdd
                        priced.priceChanged shouldBe false
                    }
                }
            }

            test("the revision is stable while nothing changes and changes when a current price changes") {
                checkAll(arbCart, Arb.int(1..1000)) { cart, delta ->
                    val atAdd = quotesAtAdd(cart)

                    cart.priced(atAdd, BRL).revision shouldBe cart.priced(atAdd, BRL).revision
                    cart.lines.forEach { target ->
                        val moved =
                            atAdd + (target.productId to quote(target.productId, target.priceAtAdd.amountMinor + delta))
                        cart.priced(moved, BRL).revision shouldNotBe cart.priced(atAdd, BRL).revision
                    }
                }
            }

            test("the revision is opaque printable ASCII and depends on the cart, its version and its lines") {
                val cart = cartOf(listOf(line(quantity = 2)))
                val revision = revisionOf(cart)

                revision.value.matches(Regex("rev-[0-9a-f]{32}")) shouldBe true
                revisionOf(cart.copy(version = cart.version + 1)) shouldNotBe revision
                revisionOf(cart.copy(id = cartOf().id)) shouldNotBe revision
                revisionOf(cart.copy(lines = cart.lines.map { it.copy(quantity = quantity(3)) })) shouldNotBe revision
                revisionOf(cart.copy(lines = cart.lines.map { it.copy(productId = productId()) })) shouldNotBe revision
                revisionOf(cart.copy(lines = cart.lines.reversed())) shouldBe revision
            }
        }

        context("ordered lines") {
            test("bought units leave the cart and lines without units disappear") {
                val kept = line(quantity = 2)
                val partly = line(quantity = 3)
                val gone = line(quantity = 1)
                val cart = cartOf(listOf(kept, partly, gone))

                val after =
                    cart.removeOrdered(
                        listOf(
                            OrderedItem(partly.productId, 1),
                            OrderedItem(partly.productId, 1),
                            OrderedItem(gone.productId, 2),
                            OrderedItem(productId(), 1),
                        ),
                        LATER,
                    )

                after.lines shouldContainExactly listOf(kept, partly.copy(quantity = quantity(1)))
                after.version shouldBe cart.version + 1
                after.updatedAt shouldBe LATER
            }

            test("a line added after the payment is not taken out by that order's late event") {
                val boughtBefore = line(quantity = 2)
                val boughtAgain = line(quantity = 1).copy(addedAt = LATER)
                val atPayment = line(quantity = 1).copy(addedAt = LATER)
                val cart = cartOf(listOf(boughtBefore, boughtAgain, atPayment))
                val paidAt = LATER.minusSeconds(1)
                val ordered = cart.lines.map { OrderedItem(it.productId, 1) }

                val after = cart.removeOrdered(ordered, LATER, paidAt)

                after.lines shouldContainExactly
                    listOf(boughtBefore.copy(quantity = quantity(1)), boughtAgain, atPayment)
                cart.removeOrdered(ordered, LATER, LATER).lines shouldContainExactly
                    listOf(boughtBefore.copy(quantity = quantity(1)))
                cart.removeOrdered(listOf(OrderedItem(boughtAgain.productId, 1)), LATER, paidAt) shouldBeSameInstanceAs
                    cart
            }

            test("an order that bought nothing from the cart leaves it untouched") {
                checkAll(arbCart) { cart ->
                    cart.removeOrdered(listOf(OrderedItem(productId(), 1)), LATER) shouldBeSameInstanceAs cart
                    cart.removeOrdered(cart.lines.map { OrderedItem(it.productId, 0) }, LATER) shouldBeSameInstanceAs
                        cart
                }
            }

            test("an order that bought everything empties the cart") {
                checkAll(arbCart) { cart ->
                    val after =
                        cart.removeOrdered(
                            cart.lines.map { OrderedItem(it.productId, it.quantity.value) },
                            LATER,
                        )

                    after.lines.shouldBeEmpty()
                }
            }
        }

        context("merge") {
            test("quantities are summed and capped at the stock, every capped product is reported") {
                checkAll(arbQuantity, arbQuantity, Arb.int(0..120)) { held, incoming, available ->
                    val product = productId()
                    val accountLine = line(product, held)
                    val account = cartOf(listOf(accountLine))
                    val anonymous = cartOf(listOf(line(product, incoming)))
                    val requested = held + incoming
                    val applied = minOf(requested, available, Quantity.MAX)

                    val outcome =
                        account.mergeFrom(
                            anonymous,
                            mapOf(product to quote(product, available = available)),
                            ::newLineId,
                            LATER,
                        )

                    if (applied == 0) {
                        outcome.cart.lineFor(product).shouldBeNull()
                    } else {
                        outcome.cart.lines shouldContainExactly listOf(accountLine.copy(quantity = quantity(applied)))
                    }
                    outcome.cappedLines shouldBe
                        if (applied < requested) listOf(CappedLine(product, requested, applied)) else emptyList()
                    outcome.cart.version shouldBe account.version + 1
                    outcome.cart.updatedAt shouldBe LATER
                }
            }

            test("new products join with their anonymous snapshots under a fresh line id") {
                checkAll(arbCart, arbCart) { account, anonymous ->
                    val fresh = anonymous.lines.associate { it.productId to newLineId() }
                    val quotes =
                        anonymous.lines.associate {
                            it.productId to
                                quote(it.productId, available = Quantity.MAX)
                        }

                    val merged = account.mergeFrom(anonymous, quotes, fresh.values.iterator()::next, LATER).cart

                    merged.lines.take(account.lines.size) shouldBe account.lines
                    merged.lines.drop(account.lines.size) shouldBe
                        anonymous.lines.map { it.copy(id = fresh.getValue(it.productId)) }
                }
            }

            test("withdrawn and unknown products are dropped and reported with 0 applied") {
                val withdrawn = line(quantity = 2)
                val unknown = line(quantity = 1)
                val held = line(withdrawn.productId, 3)
                val accountOnly = line()
                val account = cartOf(listOf(held, accountOnly))
                val anonymous = cartOf(listOf(withdrawn, unknown))
                val quotes = mapOf(withdrawn.productId to quote(withdrawn.productId, saleState = SaleState.WITHDRAWN))

                val outcome = account.mergeFrom(anonymous, quotes, ::newLineId, LATER)

                outcome.cart.lines shouldContainExactly listOf(accountOnly)
                outcome.cappedLines shouldContainExactly
                    listOf(CappedLine(withdrawn.productId, 5, 0), CappedLine(unknown.productId, 1, 0))
            }

            test("a new product that does not fit in a full account cart is reported with 0 applied") {
                val full = fullCart()
                val extra = line(quantity = 2)
                val topUp = line(full.lines.first().productId, 1)
                val quotes =
                    listOf(extra, topUp).associate { it.productId to quote(it.productId, available = Quantity.MAX) }

                val outcome = full.mergeFrom(cartOf(listOf(extra, topUp)), quotes, ::newLineId, LATER)

                outcome.cart.lines shouldHaveSize Cart.MAX_LINES
                outcome.cart.lines
                    .first()
                    .quantity shouldBe quantity(2)
                outcome.cappedLines shouldContainExactly listOf(CappedLine(extra.productId, 2, 0))
                val roomy = cartOf(full.lines.drop(1))
                roomy.mergeFrom(cartOf(listOf(extra)), quotes, ::newLineId, LATER).cappedLines.shouldBeEmpty()
            }

            test("merging an empty anonymous cart still produces a new revision") {
                checkAll(arbCart) { account ->
                    val merged = account.mergeFrom(cartOf(), emptyMap(), ::newLineId, LATER).cart

                    merged.lines shouldBe account.lines
                    revisionOf(merged) shouldNotBe revisionOf(account)
                }
            }
        }

        context("accessors") {
            test("lines are found by id and by product") {
                checkAll(arbCart) { cart ->
                    cart.lines.forEach { target ->
                        cart.line(target.id) shouldBe target
                        cart.lineFor(target.productId) shouldBe target
                    }
                    cart.line(newLineId()).shouldBeNull()
                    cart.lineFor(productId()).shouldBeNull()
                    cart.productIds shouldBe cart.lines.map { it.productId }.toSet()
                }
            }

            test("a new cart is empty at version 0") {
                val cart = Cart.new(Cart.NO_CART, CartOwner.Anonymous("hash"), T0)

                cart.lines.shouldBeEmpty()
                cart.version shouldBe 0
                cart.updatedAt shouldBe T0
                cart.id.value.toString() shouldBe "00000000-0000-0000-0000-000000000000"
                cartOf(List(SMALL_CART) { line() }).productIds shouldHaveSize SMALL_CART
            }
        }
    })

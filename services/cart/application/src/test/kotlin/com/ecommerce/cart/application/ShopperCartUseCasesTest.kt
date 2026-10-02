package com.ecommerce.cart.application

import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartOwner
import com.ecommerce.cart.domain.LineId
import com.ecommerce.cart.domain.Quantity
import com.ecommerce.cart.domain.SaleState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ShopperCartUseCasesTest :
    FunSpec({
        context("get cart") {
            test("a caller without any cart sees an empty cart and nothing is stored or asked") {
                val harness = Harness()
                val account = accountId()

                listOf(CartIdentity.Unidentified, CartIdentity.Account(account)).forEach { identity ->
                    val cart = GetCart(harness.store)(identity).value()

                    cart.cart.id shouldBe Cart.NO_CART
                    cart.lines.shouldBeEmpty()
                    cart.total shouldBe money(0)
                    cart.cart.updatedAt shouldBe NOW
                }
                GetCart(harness.store)(CartIdentity.Account(account)).value().cart.owner shouldBe
                    CartOwner.Account(account)
                GetCart(harness.store)(CartIdentity.Unidentified).value().cart.owner shouldBe CartOwner.Anonymous("")
                harness.carts.carts.values
                    .shouldBeEmpty()
                harness.catalog.asked.shouldBeEmpty()
            }

            test("an unknown anonymous token is refused") {
                GetCart(Harness().store)(CartIdentity.Anonymous("unknown")).error() shouldBe CartError.CartNotFound
            }

            test("a stored cart is shown at the catalogue's current prices") {
                val held = line(quantity = 2, price = 1000)
                val harness = Harness(quote(held.productId, price = 1100))
                val stored = anonymousCart("hash", held)
                harness.carts.store(stored)

                val cart = GetCart(harness.store)(CartIdentity.Anonymous("hash")).value()

                cart.cart shouldBe stored
                cart.lines.single().currentPrice shouldBe money(1100)
                cart.lines.single().priceChanged shouldBe true
                cart.total shouldBe money(2200)
                harness.catalog.asked shouldBe listOf(setOf(held.productId))
            }
        }

        context("add line") {
            test("the first anonymous write creates a cart owned by a new token") {
                checkAll(Arb.int(1..Quantity.MAX)) { requested ->
                    val product = quote(available = Quantity.MAX)
                    val harness = Harness(product)

                    val added =
                        AddLine(
                            harness.store,
                            harness.tokens,
                        )(CartIdentity.Unidentified, product.productId, requested).value()

                    val token = added.issuedToken.shouldNotBeNull()
                    harness.issued shouldContainExactly listOf(token)
                    val stored =
                        harness.carts.carts.values
                            .single()
                    stored.owner shouldBe CartOwner.Anonymous(token.hash)
                    stored.version shouldBe 1
                    stored.updatedAt shouldBe NOW
                    stored.lines.single().quantity shouldBe quantity(requested)
                    added.cart.cart shouldBe stored
                    added.cart.total shouldBe money(1000L * requested)
                }
            }

            test("an account's first write creates its account cart without a token") {
                val product = quote()
                val harness = Harness(product)
                val account = accountId()

                val added =
                    AddLine(
                        harness.store,
                        harness.tokens,
                    )(CartIdentity.Account(account), product.productId, 2).value()

                added.issuedToken.shouldBeNull()
                harness.issued.shouldBeEmpty()
                harness.carts
                    .ofAccount(account)
                    .shouldNotBeNull()
                    .lines
                    .single()
                    .quantity shouldBe quantity(2)
            }

            test("a later write adds to the stored cart, summing quantities of the same product") {
                val held = line(quantity = 2)
                val harness = Harness(quote(held.productId, available = 10))
                val stored = anonymousCart("hash", held)
                harness.carts.store(stored)

                val added =
                    AddLine(
                        harness.store,
                        harness.tokens,
                    )(CartIdentity.Anonymous("hash"), held.productId, 3).value()

                added.issuedToken.shouldBeNull()
                val updated = harness.carts.carts.getValue(stored.id)
                updated.version shouldBe stored.version + 1
                updated.lines.single().quantity shouldBe quantity(5)
                added.cart.cart shouldBe updated
            }

            test("refusals store nothing") {
                val withdrawn = quote(saleState = SaleState.WITHDRAWN)
                val scarce = quote(available = 3)
                val harness = Harness(withdrawn, scarce)
                val add = AddLine(harness.store, harness.tokens)
                val unknown = productId()

                add(CartIdentity.Unidentified, scarce.productId, 0).error() shouldBe CartError.InvalidQuantity(0)
                harness.catalog.asked.shouldBeEmpty()
                add(CartIdentity.Unidentified, unknown, 1).error() shouldBe CartError.ProductNotFound(unknown)
                add(CartIdentity.Unidentified, withdrawn.productId, 1).error() shouldBe
                    CartError.ProductUnavailable(withdrawn.productId)
                add(CartIdentity.Unidentified, scarce.productId, 4).error() shouldBe
                    CartError.InsufficientStock(scarce.productId, 4, 3)
                add(CartIdentity.Anonymous("unknown"), scarce.productId, 1).error() shouldBe CartError.CartNotFound
                harness.carts.carts.values
                    .shouldBeEmpty()
            }

            test("losing the race for a new cart or for an update is a concurrent update") {
                val product = quote()
                val harness = Harness(product)
                val add = AddLine(harness.store, harness.tokens)
                harness.carts.losingInserts = 1

                add(CartIdentity.Account(accountId()), product.productId, 1).error() shouldBe CartError.ConcurrentUpdate
                harness.carts.carts.values
                    .shouldBeEmpty()

                val stored = anonymousCart("hash", line())
                harness.carts.store(stored)
                harness.carts.losingUpdates = 1
                add(CartIdentity.Anonymous("hash"), product.productId, 1).error() shouldBe CartError.ConcurrentUpdate
                harness.carts.carts.getValue(stored.id) shouldBe stored
            }
        }

        context("update quantity") {
            test("sets the quantity within the stock and returns the priced cart") {
                checkAll(Arb.int(1..20)) { wanted ->
                    val held = line(quantity = 1)
                    val harness = Harness(quote(held.productId, price = 700, available = 20))
                    val stored = anonymousCart("hash", held)
                    harness.carts.store(stored)

                    val cart =
                        UpdateLineQuantity(
                            harness.store,
                        )(CartIdentity.Anonymous("hash"), held.id, wanted).value()

                    cart.lines
                        .single()
                        .line.quantity shouldBe quantity(wanted)
                    cart.total shouldBe money(700L * wanted)
                    harness.carts.carts.getValue(stored.id) shouldBe cart.cart
                    cart.cart.version shouldBe stored.version + 1
                }
            }

            test("a quantity of 0 removes the line without asking the catalogue for a quote") {
                val held = line()
                val kept = line()
                val harness = Harness()
                val account = accountId()
                harness.carts.store(accountCart(account, held, kept))

                val cart = UpdateLineQuantity(harness.store)(CartIdentity.Account(account), held.id, 0).value()

                cart.cart.lines shouldContainExactly listOf(kept)
                harness.carts.ofAccount(account) shouldBe cart.cart
                harness.catalog.asked shouldBe listOf(setOf(kept.productId))
            }

            test("unknown lines, missing carts, gone products and stock shortages are refused") {
                val held = line()
                val gone = line()
                val harness = Harness(quote(held.productId, available = 2))
                harness.carts.store(anonymousCart("hash", held, gone))
                val update = UpdateLineQuantity(harness.store)
                val unknown = LineId(UUID.randomUUID())
                val anonymous = CartIdentity.Anonymous("hash")

                update(anonymous, unknown, 1).error() shouldBe CartError.LineNotFound(unknown)
                update(CartIdentity.Unidentified, held.id, 1).error() shouldBe CartError.LineNotFound(held.id)
                update(CartIdentity.Anonymous("other"), held.id, 1).error() shouldBe CartError.CartNotFound
                update(anonymous, gone.id, 1).error() shouldBe CartError.ProductUnavailable(gone.productId)
                update(anonymous, held.id, 3).error() shouldBe CartError.InsufficientStock(held.productId, 3, 2)
                harness.carts.updates shouldBe 0
            }
        }

        context("remove line and clear") {
            test("removing a line stores and prices the rest") {
                val held = line()
                val kept = line(price = 300)
                val harness = Harness(quote(kept.productId, price = 300))
                val stored = anonymousCart("hash", held, kept)
                harness.carts.store(stored)

                val cart = RemoveLine(harness.store)(CartIdentity.Anonymous("hash"), held.id).value()

                cart.cart.lines shouldContainExactly listOf(kept)
                cart.total shouldBe money(300)
                harness.carts.carts.getValue(stored.id) shouldBe cart.cart
            }

            test("removing an unknown line or from no cart is refused") {
                val harness = Harness()
                harness.carts.store(anonymousCart("hash", line()))
                val unknown = LineId(UUID.randomUUID())

                RemoveLine(harness.store)(CartIdentity.Anonymous("hash"), unknown).error() shouldBe
                    CartError.LineNotFound(unknown)
                RemoveLine(harness.store)(CartIdentity.Unidentified, unknown).error() shouldBe
                    CartError.LineNotFound(unknown)
                harness.carts.losingUpdates = 1
                val held =
                    harness.carts.carts.values
                        .single()
                        .lines
                        .single()
                RemoveLine(harness.store)(CartIdentity.Anonymous("hash"), held.id).error() shouldBe
                    CartError.ConcurrentUpdate
            }

            test("clearing empties a cart with lines and is a no-op otherwise") {
                val harness = Harness()
                val account = accountId()
                val stored = accountCart(account, line(), line())
                val empty = anonymousCart("empty")
                harness.carts.store(stored, empty)
                val clear = ClearCart(harness.store)

                clear(CartIdentity.Account(account)).value()
                clear(CartIdentity.Anonymous("empty")).value()
                clear(CartIdentity.Unidentified).value()
                clear(CartIdentity.Account(accountId())).value()

                val cleared = harness.carts.carts.getValue(stored.id)
                cleared.lines.shouldBeEmpty()
                cleared.version shouldBe stored.version + 1
                harness.carts.carts.getValue(empty.id) shouldBe empty
                harness.carts.updates shouldBe 1
                clear(CartIdentity.Anonymous("unknown")).error() shouldBe CartError.CartNotFound
            }
        }

        context("store") {
            test("time is truncated to microseconds and ids come from the id source") {
                val fixedId = UUID.randomUUID()
                val clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00.123456789Z"), ZoneOffset.UTC)
                val store = CartStore(InMemoryCarts(), FakeCatalog(), BRL, clock) { fixedId }

                store.now() shouldBe Instant.parse("2026-10-02T10:00:00.123456Z")
                store.newCartId().value shouldBe fixedId
                store.newLineId().value shouldBe fixedId
                store.currency shouldBe BRL
            }

            test("a priced empty cart asks the catalogue nothing and a full one asks once") {
                val harness = Harness()
                val stored = anonymousCart("hash", line(), line())

                harness.store.price(anonymousCart("empty")).total shouldBe money(0)
                harness.catalog.asked.shouldBeEmpty()
                harness.store.price(stored).revision shouldNotBe harness.store.price(anonymousCart("empty")).revision
                harness.catalog.asked shouldHaveSize 1
            }
        }
    })

package com.ecommerce.cart.application

import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.OrderedItem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.Duration
import java.time.Instant

class AccountCartUseCasesTest :
    FunSpec({
        test("the internal view carries the current revision and the total at add prices") {
            val account = accountId()
            val first = line(quantity = 1, price = 14900)
            val second = line(quantity = 2, price = 2450)
            val harness = Harness(quote(first.productId, price = 15000), quote(second.productId, price = 2450))
            val stored = accountCart(account, first, second)
            harness.carts.store(stored)

            val view = GetAccountCart(harness.store)(account).shouldNotBeNull()

            view.totalAtAdd shouldBe money(19800)
            view.priced.total shouldBe money(19900)
            view.priced.cart shouldBe stored
            view.priced.revision shouldBe harness.store.price(stored).revision
            GetAccountCart(harness.store)(accountId()).shouldBeNull()
        }

        test("clearing an account cart deletes it, is idempotent, and the account reads an empty cart again") {
            val account = accountId()
            val harness = Harness()
            val other = accountCart(accountId(), line())
            harness.carts.store(accountCart(account, line(), line()), other)
            val clear = ClearAccountCart(harness.store)

            clear(account).value()
            clear(account).value()
            clear(accountId()).value()

            harness.carts.ofAccount(account).shouldBeNull()
            harness.carts.carts.values
                .toList() shouldContainExactly listOf(other)
            harness.carts.deletes shouldBe 1
            harness.carts.updates shouldBe 0
            val view = GetCart(harness.store)(CartIdentity.Account(account)).value()
            view.cart.id shouldBe Cart.NO_CART
            view.cart.lines.shouldBeEmpty()
            GetAccountCart(harness.store)(account).shouldBeNull()
        }

        test("an empty account cart left behind is deleted by the next order clearing it") {
            val account = accountId()
            val harness = Harness()
            harness.carts.store(accountCart(account))

            ClearAccountCart(harness.store)(account).value()
            harness.carts.ofAccount(account).shouldBeNull()

            harness.carts.store(accountCart(account))
            RemoveOrderedLines(harness.store)(account, listOf(OrderedItem(productId(), 1))).value()
            harness.carts.ofAccount(account).shouldBeNull()
        }

        test("a background change retries a lost race and gives up after three attempts") {
            val account = accountId()
            val harness = Harness()
            harness.carts.store(accountCart(account, line()))
            val clear = ClearAccountCart(harness.store)

            harness.carts.losingDeletes = CONFLICT_ATTEMPTS
            clear(account).error() shouldBe CartError.ConcurrentUpdate
            harness.carts.deletes shouldBe CONFLICT_ATTEMPTS
            harness.carts.ofAccount(account).shouldNotBeNull()

            harness.carts.losingDeletes = CONFLICT_ATTEMPTS - 1
            clear(account).value()
            harness.carts.deletes shouldBe 2 * CONFLICT_ATTEMPTS
            harness.carts.ofAccount(account).shouldBeNull()

            val bought = line(quantity = 2)
            harness.carts.store(accountCart(account, bought))
            val remove = RemoveOrderedLines(harness.store)
            harness.carts.losingUpdates = CONFLICT_ATTEMPTS
            remove(account, listOf(OrderedItem(bought.productId, 1))).error() shouldBe CartError.ConcurrentUpdate
            harness.carts.updates shouldBe CONFLICT_ATTEMPTS
            harness.carts.losingUpdates = CONFLICT_ATTEMPTS - 1
            remove(account, listOf(OrderedItem(bought.productId, 1))).value()
            harness.carts.updates shouldBe 2 * CONFLICT_ATTEMPTS
            harness.carts
                .ofAccount(account)
                .shouldNotBeNull()
                .lines shouldContainExactly listOf(bought.copy(quantity = quantity(1)))
        }

        test("an order paid takes the ordered quantities out of the account cart") {
            val account = accountId()
            val bought = line(quantity = 3)
            val kept = line(quantity = 1)
            val harness = Harness()
            harness.carts.store(accountCart(account, bought, kept))
            val remove = RemoveOrderedLines(harness.store)

            remove(account, listOf(OrderedItem(bought.productId, 2))).value()
            remove(accountId(), listOf(OrderedItem(bought.productId, 2))).value()

            harness.carts
                .ofAccount(account)
                .shouldNotBeNull()
                .lines shouldContainExactly
                listOf(bought.copy(quantity = quantity(1)), kept)
            remove(account, listOf(OrderedItem(productId(), 1))).value()
            harness.carts.updates shouldBe 1
            harness.carts.deletes shouldBe 0
        }

        test("an order paid leaves the lines added after its payment alone") {
            val account = accountId()
            val paidAt = Instant.parse("2026-10-03T21:00:00Z")
            val again = line(quantity = 1).copy(addedAt = paidAt.plusSeconds(2))
            val harness = Harness()
            harness.carts.store(accountCart(account, again))

            RemoveOrderedLines(harness.store)(account, listOf(OrderedItem(again.productId, 1)), paidAt).value()

            harness.carts
                .ofAccount(account)
                .shouldNotBeNull()
                .lines shouldContainExactly listOf(again)
            harness.carts.updates shouldBe 0
        }

        test("an order that buys every remaining unit deletes the account cart; other units keep it") {
            checkAll(Arb.list(Arb.int(1, 5), 1..4), Arb.list(Arb.int(0, 6), 4..4)) { held, bought ->
                val account = accountId()
                val lines = held.map { line(quantity = it) }
                val harness = Harness()
                harness.carts.store(accountCart(account, *lines.toTypedArray()))
                val ordered = lines.zip(bought).map { (line, units) -> OrderedItem(line.productId, units) }

                RemoveOrderedLines(harness.store)(account, ordered).value()

                val remaining =
                    lines.zip(bought).mapNotNull { (line, units) ->
                        val left = line.quantity.value - units
                        if (left > 0) line.copy(quantity = quantity(left)) else null
                    }
                val stored = harness.carts.ofAccount(account)
                if (remaining.isEmpty()) {
                    stored.shouldBeNull()
                    GetCart(harness.store)(CartIdentity.Account(account))
                        .value()
                        .cart.lines
                        .shouldBeEmpty()
                } else {
                    stored.shouldNotBeNull().lines shouldContainExactly remaining
                }
            }
        }

        test("a deleted account loses its cart") {
            val account = accountId()
            val harness = Harness()
            harness.carts.store(accountCart(account, line()), anonymousCart("hash"))

            DeleteAccountCart(harness.store)(account) shouldBe true
            DeleteAccountCart(harness.store)(account) shouldBe false
            harness.carts.ofAccount(account).shouldBeNull()
            harness.carts.carts.values
                .single()
                .lines
                .shouldBeEmpty()
        }

        test("idle anonymous carts are purged after the idle timeout") {
            val harness = Harness()
            val old = anonymousCart("old").copy(updatedAt = NOW.minus(Duration.ofDays(31)))
            val recent = anonymousCart("recent").copy(updatedAt = NOW.minus(Duration.ofDays(29)))
            val account = accountCart(accountId()).copy(updatedAt = NOW.minus(Duration.ofDays(400)))
            harness.carts.store(old, recent, account)

            PurgeIdleCarts(harness.store)() shouldBe 1

            harness.carts.purgeCutoff shouldBe NOW.minus(PurgeIdleCarts.DEFAULT_IDLE_TIMEOUT)
            harness.carts.carts.values
                .toList() shouldContainExactly listOf(recent, account)
            PurgeIdleCarts(harness.store, Duration.ofDays(1))() shouldBe 1
            harness.carts.purgeCutoff shouldBe NOW.minus(Duration.ofDays(1))
            PurgeIdleCarts.DEFAULT_IDLE_TIMEOUT shouldBe Duration.ofDays(30)
        }

        test("an issued token never shows its value") {
            IssuedToken("secret", "hash").toString() shouldBe "IssuedToken(****)"
        }
    })

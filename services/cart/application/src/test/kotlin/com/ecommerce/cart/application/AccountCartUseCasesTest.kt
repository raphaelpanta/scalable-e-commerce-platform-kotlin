package com.ecommerce.cart.application

import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.OrderedItem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

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

        test("clearing an account cart removes its lines, keeps the cart and is idempotent") {
            val account = accountId()
            val harness = Harness()
            val stored = accountCart(account, line(), line())
            harness.carts.store(stored)
            val clear = ClearAccountCart(harness.store)

            clear(account).value()
            clear(account).value()
            clear(accountId()).value()

            val cleared = harness.carts.ofAccount(account).shouldNotBeNull()
            cleared.id shouldBe stored.id
            cleared.lines.shouldBeEmpty()
            cleared.version shouldBe stored.version + 1
            cleared.updatedAt shouldBe NOW
            harness.carts.updates shouldBe 1
        }

        test("a background change retries a lost race and gives up after three attempts") {
            val account = accountId()
            val harness = Harness()
            harness.carts.store(accountCart(account, line()))
            val clear = ClearAccountCart(harness.store)

            harness.carts.losingUpdates = CONFLICT_ATTEMPTS
            clear(account).error() shouldBe CartError.ConcurrentUpdate
            harness.carts.updates shouldBe CONFLICT_ATTEMPTS

            harness.carts.losingUpdates = CONFLICT_ATTEMPTS - 1
            clear(account).value()
            harness.carts.updates shouldBe 2 * CONFLICT_ATTEMPTS
            harness.carts
                .ofAccount(account)
                .shouldNotBeNull()
                .lines
                .shouldBeEmpty()
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

package com.ecommerce.cart.application

import com.ecommerce.cart.domain.CappedLine
import com.ecommerce.cart.domain.CartError
import com.ecommerce.cart.domain.CartOwner
import com.ecommerce.cart.domain.Quantity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class MergeCartsTest :
    FunSpec({
        fun Harness.merge() = MergeCarts(store, transactions, events)

        test("the anonymous cart is merged, consumed and announced; the same token then answers not found") {
            checkAll(Arb.int(1..Quantity.MAX), Arb.int(1..Quantity.MAX), Arb.int(0..120)) { held, incoming, available ->
                val account = accountId()
                val shared = line(quantity = held)
                val other = line(quantity = 1)
                val harness = Harness(quote(shared.productId, available = available), quote(other.productId))
                val target = accountCart(account, shared)
                harness.carts.store(target, anonymousCart("hash", line(shared.productId, incoming), other))
                val requested = held + incoming
                val applied = minOf(requested, available, Quantity.MAX)

                val result = harness.merge()(account, "hash").value()

                harness.carts.findByTokenHash("hash").shouldBeNull()
                val merged = harness.carts.ofAccount(account).shouldNotBeNull()
                merged.id shouldBe target.id
                merged.version shouldBe target.version + 1
                merged.lineFor(shared.productId)?.quantity?.value shouldBe applied.takeIf { it > 0 }
                merged.lineFor(other.productId).shouldNotBeNull().quantity shouldBe quantity(1)
                result.cart.cart shouldBe merged
                val capped =
                    if (applied <
                        requested
                    ) {
                        listOf(CappedLine(shared.productId, requested, applied))
                    } else {
                        emptyList()
                    }
                result.cappedLines shouldBe capped
                harness.events.merged shouldContainExactly
                    listOf(CartMerged(account, merged.id, 1, merged.lines.size, capped))
                harness.merge()(account, "hash").error() shouldBe CartError.CartNotFound
            }
        }

        test("an account without a cart gets one holding the anonymous lines") {
            val account = accountId()
            val held = line(quantity = 2)
            val harness = Harness(quote(held.productId))
            harness.carts.store(anonymousCart("hash", held))

            val result = harness.merge()(account, "hash").value()

            val created = harness.carts.ofAccount(account).shouldNotBeNull()
            created.owner shouldBe CartOwner.Account(account)
            created.version shouldBe 1
            created.lines.single().quantity shouldBe quantity(2)
            created.lines.single().id shouldBe
                result.cart.cart.lines
                    .single()
                    .id
            result.cappedLines.shouldBeEmpty()
            harness.carts.carts.values
                .single() shouldBe created
            val event = harness.events.merged.single()
            event.accountId shouldBe account
            event.cartId shouldBe created.id
            event.mergedCartCount shouldBe 1
            event.lineCount shouldBe 1
            event.cappedLines.shouldBeEmpty()
        }

        test("losing the race for the anonymous cart or the account cart changes nothing and announces nothing") {
            val account = accountId()
            val held = line()
            val harness = Harness(quote(held.productId))
            val anonymous = anonymousCart("hash", held)
            val target = accountCart(account, line())
            harness.carts.store(anonymous, target)

            harness.carts.losingDeletes = 1
            harness.merge()(account, "hash").error() shouldBe CartError.ConcurrentUpdate
            harness.carts.losingUpdates = 1
            harness.merge()(account, "hash").error() shouldBe CartError.ConcurrentUpdate

            harness.transactions.rollbacks shouldBe 2
            harness.carts.carts.values
                .toList() shouldContainExactly listOf(anonymous, target)
            harness.events.merged.shouldBeEmpty()
        }
    })

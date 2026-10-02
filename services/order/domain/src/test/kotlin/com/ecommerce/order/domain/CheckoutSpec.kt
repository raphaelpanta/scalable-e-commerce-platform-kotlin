package com.ecommerce.order.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant
import java.util.UUID

private fun cartLine(
    priceAtAdd: Long,
    quantity: Int = 1,
    productId: ProductId = ProductId(UUID.randomUUID()),
): CartLine =
    CartLine(UUID.randomUUID().toString(), productId, "SKU-1", "Mug", Quantity(quantity), Money(priceAtAdd, "BRL"))

private fun priceOf(
    line: CartLine,
    priceMinor: Long,
    active: Boolean = true,
): ProductPrice = ProductPrice(line.productId, "SKU-NEW", "Mug (new)", Money(priceMinor, "BRL"), 5, active)

class CheckoutSpec :
    FunSpec({
        test("changed lines are the lines whose current price differs from the price at add") {
            checkAll(Arb.list(Arb.long(1L..100_000L), 1..8), Arb.int(0..1_000)) { prices, delta ->
                val cart = Cart("rev-1", prices.map { cartLine(it) })
                val current =
                    cart.lines.mapIndexed { index, line ->
                        priceOf(
                            line,
                            line.priceAtAdd.amountMinor + index * delta,
                        )
                    }

                val changed = Checkout.changedLines(cart, current)

                changed.map { it.productId } shouldBe
                    cart.lines.filterIndexed { index, _ -> index * delta != 0 }.map { it.productId }
                changed.forEach { line ->
                    val cartLine = cart.lines.single { it.productId == line.productId }
                    line.lineId shouldBe cartLine.lineId
                    line.oldPrice shouldBe cartLine.priceAtAdd
                    line.newPrice shouldBe current.single { it.productId == line.productId }.price
                }
            }
        }

        test("products the catalogue no longer knows are not listed as changed") {
            val cart = Cart("rev-1", listOf(cartLine(100), cartLine(200)))
            Checkout.changedLines(cart, emptyList()).shouldBeEmpty()
        }

        test("lines are frozen at the current catalogue price and name") {
            checkAll(Arb.list(Arb.long(1L..100_000L), 1..8)) { prices ->
                val cart = Cart("rev-1", prices.map { cartLine(it * 2, quantity = 3) })
                val current = cart.lines.map { priceOf(it, it.priceAtAdd.amountMinor / 2) }

                val lines = checkNotNull(Checkout.freezeLines(cart, current).getOrNull())

                lines.map { it.unitPrice.amountMinor } shouldBe prices
                lines.forEach {
                    it.name shouldBe "Mug (new)"
                    it.sku shouldBe "SKU-NEW"
                    it.quantity shouldBe Quantity(3)
                }
                Checkout.stockLines(lines) shouldBe lines.map { StockLine(it.productId, Quantity(3)) }
            }
        }

        test("unknown and withdrawn products refuse the checkout as unavailable with nothing available") {
            val known = cartLine(100)
            val withdrawn = cartLine(200, quantity = 2)
            val unknown = cartLine(300, quantity = 4)
            val cart = Cart("rev-1", listOf(known, withdrawn, unknown))

            Checkout
                .freezeLines(
                    cart,
                    listOf(priceOf(known, 100), priceOf(withdrawn, 200, active = false)),
                ).leftOrNull() shouldBe
                OrderError.InsufficientStock(
                    listOf(
                        UnavailableLine(withdrawn.productId, "Mug", 2, 0),
                        UnavailableLine(unknown.productId, "Mug", 4, 0),
                    ),
                )
        }

        test("catalogue shortages are reported with the cart's product names") {
            val line = cartLine(100, quantity = 2)
            val stranger = ProductId(UUID.randomUUID())
            Checkout
                .insufficientStock(
                    Cart("rev-1", listOf(line)),
                    listOf(StockShortage(line.productId, 2, 1), StockShortage(stranger, 1, 0)),
                ).lines shouldContainExactly
                listOf(UnavailableLine(line.productId, "Mug", 2, 1), UnavailableLine(stranger, "", 1, 0))
        }

        test("the request fingerprint changes with every field and is stable otherwise") {
            checkAll(Arb.string(1..20), Arb.string(1..20)) { revision, token ->
                val address = AddressId(UUID.randomUUID())
                val request = CheckoutRequest(address, revision, "card", token)
                request.fingerprint() shouldBe request.copy().fingerprint()
                request.fingerprint() shouldHaveLength 64
                request.copy(cartRevision = revision + "x").fingerprint() shouldNotBe request.fingerprint()
                request.copy(paymentToken = token + "x").fingerprint() shouldNotBe request.fingerprint()
                request.copy(paymentMethodType = "cash").fingerprint() shouldNotBe request.fingerprint()
                request.copy(addressId = AddressId(UUID.randomUUID())).fingerprint() shouldNotBe request.fingerprint()
            }
            val address = AddressId(UUID.randomUUID())
            CheckoutRequest(address, "ab", "card", "c").fingerprint() shouldNotBe
                CheckoutRequest(address, "a", "card", "bc").fingerprint()
        }

        test("an idempotency record replays its answer for the same request only") {
            val now = Instant.parse("2026-10-02T10:15:00Z")
            val claim = IdempotencyRecord.claim(OrderFixtures.KEY, OrderFixtures.SHOPPER, "hash", now)
            claim.expiresAt shouldBe now.plusSeconds(24 * 3600)
            claim.orderId shouldBe null
            claim.replayFor("hash").getOrNull() shouldBe Replay.InProgress
            claim.replayFor("other").leftOrNull() shouldBe OrderError.IdempotencyKeyReuse

            val orderId = OrderId(UUID.randomUUID())
            val stored = StoredResponse(201, "{}")
            claim.copy(orderId = orderId).replayFor("hash").getOrNull() shouldBe Replay.InProgress
            claim.copy(response = stored).replayFor("hash").getOrNull() shouldBe Replay.InProgress
            val completed = claim.copy(orderId = orderId, response = stored)
            completed.replayFor("hash").getOrNull() shouldBe Replay.Completed(orderId, stored)
            completed.replayFor("other").leftOrNull() shouldBe OrderError.IdempotencyKeyReuse
            IdempotencyRecord.CLAIM_TIMEOUT.toMinutes() shouldBe 2
        }
    })

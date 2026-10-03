package com.ecommerce.catalog.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.time.Instant
import java.util.UUID

private val LATER: Instant = NOW.plusSeconds(60)
private val OPERATOR = AccountId(UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"))

private fun reason(text: String = "Damaged during stocktake"): StockAdjustmentReason =
    StockAdjustmentReason.of(text).value()

private fun adjust(
    level: InventoryLevel,
    delta: Int,
) = StockAdjustment.apply(AdjustmentId(UUID.randomUUID()), level, delta, reason(), OPERATOR, LATER)

class CatalogOperationsSpec :
    FunSpec({
        context("stock adjustments") {
            test("an adjustment applies its signed delta and records who, when, why and the levels") {
                checkAll(Arb.int(0..500), Arb.int(0..500), Arb.int(-600..600)) { onHand, reservedRaw, delta ->
                    val level = InventoryLevel(productId(), onHand, reservedRaw.coerceAtMost(onHand))
                    if (delta == 0) return@checkAll
                    val result = adjust(level, delta)
                    if (level.available + delta >= 0) {
                        val applied = result.value()
                        applied.level.onHand shouldBe onHand + delta
                        applied.level.reserved shouldBe level.reserved
                        applied.level.version shouldBe level.version + 1
                        with(applied.adjustment) {
                            productId shouldBe level.productId
                            this.delta shouldBe delta
                            reason shouldBe reason()
                            actorId shouldBe OPERATOR
                            at shouldBe LATER
                            previousAvailable shouldBe level.available
                            newAvailable shouldBe level.available + delta
                        }
                    } else {
                        result.error() shouldBe CatalogError.StockBelowReserved(level.productId, delta, level.available)
                    }
                }
            }

            test("a negative adjustment cannot take the available quantity below the reserved units") {
                val level = InventoryLevel(productId(), onHand = 10, reserved = 4)
                adjust(level, -6).value().level.available shouldBe 0
                adjust(level, -7).error() shouldBe CatalogError.StockBelowReserved(level.productId, -7, 6)
            }

            test("stock never exceeds one million units") {
                val level = InventoryLevel(productId(), onHand = StockLevel.MAX - 1, reserved = 0)
                adjust(level, 1).value().level.onHand shouldBe StockLevel.MAX
                val tooMuch = adjust(level, 2).error().shouldBeInstanceOf<CatalogError.Invalid>()
                tooMuch.issues.single().field shouldBe "delta"
            }

            test("a delta is non-zero and at most one million units either way") {
                StockAdjustment.delta(0).error() shouldBe FieldIssue("delta", "must not be 0")
                StockAdjustment.delta(1).value() shouldBe 1
                StockAdjustment.delta(-1).value() shouldBe -1
                StockAdjustment.delta(StockAdjustment.MAX_DELTA).value() shouldBe StockAdjustment.MAX_DELTA
                StockAdjustment.delta(-StockAdjustment.MAX_DELTA).value() shouldBe -StockAdjustment.MAX_DELTA
                StockAdjustment.delta(StockAdjustment.MAX_DELTA + 1).error().field shouldBe "delta"
                StockAdjustment.delta(-StockAdjustment.MAX_DELTA - 1).error().field shouldBe "delta"
            }

            test("the reason of an adjustment is mandatory") {
                StockAdjustmentReason.of(null).error().field shouldBe "reason"
                StockAdjustmentReason.of("  ").error().field shouldBe "reason"
            }

            test("an adjustment record keeps its invariants") {
                val id = AdjustmentId(UUID.randomUUID())
                val product = productId()
                shouldThrow<IllegalArgumentException> { StockAdjustment(id, product, 0, reason(), OPERATOR, NOW, 1, 1) }
                shouldThrow<IllegalArgumentException> { StockAdjustment(id, product, 2, reason(), OPERATOR, NOW, 1, 2) }
                shouldThrow<IllegalArgumentException> {
                    StockAdjustment(id, product, -2, reason(), OPERATOR, NOW, 1, -1)
                }
                StockAdjustment(id, product, -1, reason(), OPERATOR, NOW, 1, 0).newAvailable shouldBe 0
            }
        }

        context("withdrawing") {
            test("a withdrawn product is hidden from shoppers, visible to operators and cannot be withdrawn again") {
                val active = product()
                active.isActive shouldBe true
                active.visibleTo(operator = false) shouldBe true
                val withdrawn = active.withdraw(LATER).value()
                withdrawn.saleState shouldBe SaleState.WITHDRAWN
                withdrawn.isActive shouldBe false
                withdrawn.visibleTo(operator = false) shouldBe false
                withdrawn.visibleTo(operator = true) shouldBe true
                withdrawn.updatedAt shouldBe LATER
                withdrawn.version shouldBe active.version + 1
                withdrawn.withdraw(LATER).error() shouldBe CatalogError.AlreadyWithdrawn(active.id)
            }

            test("a product of a hidden category is off sale and hidden from shoppers, never from operators") {
                checkAll(Arb.boolean(), Arb.boolean(), Arb.boolean()) { active, hiddenCategory, operator ->
                    val stored = product(saleState = if (active) SaleState.ACTIVE else SaleState.WITHDRAWN)
                    val other = categoryId()
                    val hidden = if (hiddenCategory) setOf(stored.details.categoryId, other) else setOf(other)
                    stored.onSale(hidden) shouldBe (active && !hiddenCategory)
                    stored.visibleTo(operator, hidden) shouldBe (operator || (active && !hiddenCategory))
                }
                product().visibleTo(operator = false) shouldBe true
            }

            test("withdrawing leaves stock and open reservations untouched (existing orders are unaffected)") {
                val product = product()
                val level = InventoryLevel(product.id, onHand = 5, reserved = 2)
                product.withdraw(LATER).value().id shouldBe level.productId
                level.available shouldBe 3
            }
        }

        context("product details and images") {
            test("details report every broken rule at once") {
                val invalid = ProductDetails.of(" ", "x".repeat(4001), 0, BRL, categoryId()).error()
                invalid.issues.map { it.field } shouldContainExactly listOf("name", "description", "price.amountMinor")
                val category = categoryId()
                val valid = ProductDetails.of(" Mug ", null, 3290, BRL, category).value()
                valid.name.value shouldBe "Mug"
                valid.description shouldBe null
                valid.price shouldBe Money(3290, BRL)
                valid.categoryId shouldBe category
            }

            test("an update replaces the details and moves the version") {
                val product = product()
                val updated = product.update(details(name = "Grinder", priceMinor = 100), LATER)
                updated.details.name.value shouldBe "Grinder"
                updated.details.price.amountMinor shouldBe 100
                updated.updatedAt shouldBe LATER
                updated.createdAt shouldBe product.createdAt
                updated.version shouldBe product.version + 1
                updated.saleState shouldBe product.saleState
            }

            test("a new product is active, without images, at version 0") {
                val id = productId()
                val created = Product.create(id, Sku.generatedFor(id), details(), NOW)
                created.saleState shouldBe SaleState.ACTIVE
                created.images shouldBe emptyList()
                created.createdAt shouldBe NOW
                created.updatedAt shouldBe NOW
                created.version shouldBe 0
            }

            test("the first image and every image marked primary become the only primary image") {
                val first = image(primary = false)
                val withFirst = product().addImage(first, LATER).value()
                withFirst.images.single().primary shouldBe true
                withFirst.updatedAt shouldBe LATER
                withFirst.version shouldBe 1
                val second = image(primary = false)
                val withSecond = withFirst.addImage(second, LATER).value()
                withSecond.images.map { it.primary } shouldContainExactly listOf(true, false)
                val third = image(primary = true)
                val withThird = withSecond.addImage(third, LATER).value()
                withThird.images.map { it.primary } shouldContainExactly listOf(false, false, true)
                withThird.images.last().id shouldBe third.id
            }

            test("a product holds at most ten images") {
                val full =
                    (1..Product.MAX_IMAGES).fold(product()) { current, _ -> current.addImage(image(), NOW).value() }
                full.images shouldHaveSize Product.MAX_IMAGES
                full.addImage(image(), NOW).error() shouldBe CatalogError.TooManyImages(full.id)
                shouldThrow<IllegalArgumentException> { full.copy(images = full.images + image()) }
                shouldThrow<IllegalArgumentException> { full.copy(images = listOf(image(), image())) }
                shouldThrow<IllegalArgumentException> { full.copy(images = listOf(image(true), image(true))) }
            }
        }
    })

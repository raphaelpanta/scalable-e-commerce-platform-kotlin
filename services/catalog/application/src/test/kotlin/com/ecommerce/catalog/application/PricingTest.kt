package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.SaleState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class PricingTest :
    FunSpec({
        test("the pricing of a product reports onHand - reserved, withdrawn or not") {
            val harness = Harness()
            val active = harness.product(stock = 5, reserved = 1)
            val withdrawn = harness.product(stock = 3, saleState = SaleState.WITHDRAWN)

            GetPricing(harness.catalog)(active.id).value() shouldBe ProductPricing(active, 4)
            GetPricing(harness.catalog)(withdrawn.id).value() shouldBe ProductPricing(withdrawn, 3)
        }

        test("only an unknown product is not found") {
            val unknown = ProductId(UUID.randomUUID())
            GetPricing(Harness().catalog)(unknown).error() shouldBe CatalogError.ProductNotFound(unknown)
        }

        test("a batch follows the request order and omits unknown ids") {
            val harness = Harness()
            val first = harness.product("B", stock = 5)
            val second = harness.product("A", stock = 0)
            val unknown = ProductId(UUID.randomUUID())

            val priced = GetPricingBatch(harness.catalog)(listOf(second.id, unknown, first.id)).value()
            priced shouldContainExactly listOf(ProductPricing(second, 0), ProductPricing(first, 5))
            GetPricingBatch(harness.catalog)(listOf(unknown)).value() shouldBe emptyList()
        }

        test("a batch holds 1 to 100 distinct ids") {
            val harness = Harness()
            val invalid =
                CatalogError.Invalid(
                    arrow.core.nonEmptyListOf(FieldIssue("productIds", "must hold 1 to 100 distinct ids")),
                )
            val id = ProductId(UUID.randomUUID())
            GetPricingBatch(harness.catalog)(emptyList()).error() shouldBe invalid
            GetPricingBatch(harness.catalog)(listOf(id, id)).error() shouldBe invalid
            val batch = GetPricingBatch(harness.catalog)
            batch((0..GetPricingBatch.MAX_IDS).map { ProductId(UUID.randomUUID()) }).error() shouldBe invalid
            batch((1..GetPricingBatch.MAX_IDS).map { ProductId(UUID.randomUUID()) }).value() shouldBe emptyList()
        }
    })

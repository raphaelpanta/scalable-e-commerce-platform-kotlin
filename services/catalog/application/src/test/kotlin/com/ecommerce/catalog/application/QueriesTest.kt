package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.SaleState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

private fun page(
    page: Int = 0,
    size: Int = 20,
): PageRequest = PageRequest.of(page, size).valid()

class QueriesTest :
    FunSpec({
        context("listing") {
            test("shoppers see active products only, with availability but never the quantity") {
                val harness = Harness()
                val active = harness.product("Mug", stock = 5, reserved = 2)
                val soldOut = harness.product("Kettle", stock = 1, reserved = 1)
                harness.product("Old lamp", saleState = SaleState.WITHDRAWN)

                listOf(Caller.ANONYMOUS, SHOPPER).forEach { caller ->
                    val listed = ListProducts(harness.catalog)(caller, null, includeWithdrawn = true, page())
                    listed.items.map { it.product.id } shouldContainExactly listOf(soldOut.id, active.id)
                    listed.totalItems shouldBe 2
                    listed.items.map { it.inStock } shouldContainExactly listOf(false, true)
                    listed.items.map { it.available } shouldContainExactly listOf(0, 3)
                    listed.items.map { it.showQuantity } shouldContainExactly listOf(false, false)
                }
            }

            test("operators may include withdrawn products and are shown the quantity") {
                val harness = Harness()
                harness.product("Mug")
                val withdrawn = harness.product("Lamp", stock = 4, saleState = SaleState.WITHDRAWN)

                ListProducts(harness.catalog)(OPERATOR, null, includeWithdrawn = false, page()).totalItems shouldBe 1
                val listed = ListProducts(harness.catalog)(OPERATOR, null, includeWithdrawn = true, page())
                listed.items.map { it.product.id } shouldContainExactly
                    listOf(
                        withdrawn.id,
                        harness.products.products.keys
                            .first(),
                    )
                listed.items.first().showQuantity shouldBe true
                listed.items.first().available shouldBe 4
                listed.items.first().inStock shouldBe false
            }

            test("a category filter covers the category and its descendants, paged") {
                val harness = Harness()
                val garden = harness.category("Garden")
                val tools = harness.category("Tools", garden.id)
                val kitchen = harness.category("Kitchen")
                val rake = harness.product("Rake", categoryId = tools.id)
                val hose = harness.product("Hose", categoryId = garden.id)
                harness.product("Pan", categoryId = kitchen.id)

                val first = ListProducts(harness.catalog)(Caller.ANONYMOUS, garden.id, false, page(0, 1))
                first.items.map { it.product.id } shouldContainExactly listOf(hose.id)
                first.totalItems shouldBe 2
                first.page shouldBe 0
                first.size shouldBe 1
                val second = ListProducts(harness.catalog)(Caller.ANONYMOUS, garden.id, false, page(1, 1))
                second.items.map { it.product.id } shouldContainExactly listOf(rake.id)
            }

            test("a product without a stock record is shown as unavailable") {
                val harness = Harness()
                val product = harness.product("Ghost")
                harness.inventory.levels.clear()
                val listed = ListProducts(harness.catalog)(OPERATOR, null, false, page())
                listed.items
                    .single()
                    .product.id shouldBe product.id
                listed.items.single().available shouldBe 0
            }
        }

        context("searching") {
            test("matches are ranked by relevance and withdrawn products are never found by shoppers") {
                val harness = Harness()
                val desk = harness.product("Desk", description = "Goes well with a lantern.")
                val lantern = harness.product("Lantern lamp")
                val exact = harness.product("lantern")
                val old = harness.product("Old lantern")
                harness.product("Lantern withdrawn", saleState = SaleState.WITHDRAWN)
                harness.product("Chair")

                val found = SearchProducts(harness.catalog)(SHOPPER, "  LANTERN ", null, true, page()).value()
                found.items.map { it.product.id } shouldContainExactly listOf(exact.id, lantern.id, old.id, desk.id)
                val operatorView = SearchProducts(harness.catalog)(OPERATOR, "lantern", null, true, page()).value()
                operatorView.totalItems shouldBe 5
                operatorView.items.all { it.showQuantity } shouldBe true
            }

            test("a search term is 1 to 100 characters") {
                val harness = Harness()
                SearchProducts(harness.catalog)(SHOPPER, " ", null, false, page()).error() shouldBe
                    CatalogError.Invalid(arrow.core.nonEmptyListOf(FieldIssue("q", "must not be blank")))
            }
        }

        context("one product") {
            test("an active product is found with its availability; the quantity only for operators") {
                val harness = Harness()
                val product = harness.product(stock = 7, reserved = 2)

                val shopper = GetProduct(harness.catalog)(SHOPPER, product.id).value()
                shopper.product shouldBe product
                shopper.available shouldBe 5
                shopper.inStock shouldBe true
                shopper.showQuantity shouldBe false
                GetProduct(harness.catalog)(OPERATOR, product.id).value().showQuantity shouldBe true
            }

            test("a withdrawn product is not found by shoppers but is by operators") {
                val harness = Harness()
                val withdrawn = harness.product(saleState = SaleState.WITHDRAWN)

                GetProduct(harness.catalog)(Caller.ANONYMOUS, withdrawn.id).error() shouldBe
                    CatalogError.ProductNotFound(withdrawn.id)
                GetProduct(harness.catalog)(SHOPPER, withdrawn.id).error() shouldBe
                    CatalogError.ProductNotFound(withdrawn.id)
                GetProduct(harness.catalog)(OPERATOR, withdrawn.id).value().product shouldBe withdrawn
            }

            test("an unknown product is not found") {
                val unknown = ProductId(UUID.randomUUID())
                GetProduct(Harness().catalog)(OPERATOR, unknown).error() shouldBe CatalogError.ProductNotFound(unknown)
            }
        }

        context("categories") {
            test("categories are listed by name, optionally the children of one parent") {
                val harness = Harness()
                val outdoor = harness.category("Outdoor")
                val footwear = harness.category("Footwear")
                val tents = harness.category("Tents", outdoor.id)

                ListCategories(harness.catalog)(Caller.ANONYMOUS, null, page()).items shouldContainExactly
                    listOf(footwear, outdoor, tents)
                val children = ListCategories(harness.catalog)(SHOPPER, outdoor.id, page())
                children.items shouldContainExactly listOf(tents)
                children.totalItems shouldBe 1
            }

            test("a category is found by id; an unknown one is not found") {
                val harness = Harness()
                val category = harness.category()
                GetCategory(harness.catalog)(SHOPPER, category.id).value() shouldBe category
                val unknown = CategoryId(UUID.randomUUID())
                GetCategory(harness.catalog)(OPERATOR, unknown)
                    .error()
                    .shouldBeInstanceOf<CatalogError.CategoryNotFound>()
                    .categoryId shouldBe unknown
            }
        }

        context("callers") {
            test("only an authenticated account with the operator role is an operator") {
                OPERATOR.isOperator shouldBe true
                SHOPPER.isOperator shouldBe false
                Caller.ANONYMOUS.isOperator shouldBe false
                Caller(null, setOf(CallerRole.OPERATOR)).isOperator shouldBe false
            }
        }
    })

package com.ecommerce.catalog.application

import arrow.core.nonEmptyListOf
import com.ecommerce.catalog.application.admin.AddProductImage
import com.ecommerce.catalog.application.admin.AdjustStock
import com.ecommerce.catalog.application.admin.AuthorizeOperator
import com.ecommerce.catalog.application.admin.CategoryInput
import com.ecommerce.catalog.application.admin.CreateCategory
import com.ecommerce.catalog.application.admin.CreateProduct
import com.ecommerce.catalog.application.admin.ProductInput
import com.ecommerce.catalog.application.admin.UpdateCategory
import com.ecommerce.catalog.application.admin.UpdateProduct
import com.ecommerce.catalog.application.admin.WithdrawCategory
import com.ecommerce.catalog.application.admin.WithdrawProduct
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.CategoryStatus
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.StockLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.util.UUID

private const val ALL: Int = 100

/** Every operator use case, invoked with arbitrary (possibly invalid) input on the shelf's first records. */
private fun attempts(
    harness: Harness,
    shelf: Shelf,
    text: String,
    number: Int,
): List<Triple<OperatorAction, UUID?, suspend (Caller) -> CatalogError?>> {
    val catalog = harness.catalog
    val category = shelf.categories.first().id
    val product = shelf.products.firstOrNull()?.id ?: ProductId(UUID.randomUUID())
    val input = ProductInput(text, text, number.toLong(), text, category)
    return listOf(
        Triple(OperatorAction.CREATE_PRODUCT, null, { CreateProduct(catalog)(it, input, number, text).leftOrNull() }),
        Triple(
            OperatorAction.UPDATE_PRODUCT,
            product.value,
            { UpdateProduct(catalog)(it, product, input).leftOrNull() },
        ),
        Triple(OperatorAction.WITHDRAW_PRODUCT, product.value, { WithdrawProduct(catalog)(it, product).leftOrNull() }),
        Triple(
            OperatorAction.ADJUST_STOCK,
            product.value,
            { AdjustStock(catalog)(it, product, number, text).leftOrNull() },
        ),
        Triple(
            OperatorAction.ADD_PRODUCT_IMAGE,
            product.value,
            { AddProductImage(catalog)(it, product, text, text, true).leftOrNull() },
        ),
        Triple(
            OperatorAction.CREATE_CATEGORY,
            null,
            { CreateCategory(catalog)(it, CategoryInput(text, text, category)).leftOrNull() },
        ),
        Triple(
            OperatorAction.UPDATE_CATEGORY,
            category.value,
            { UpdateCategory(catalog)(it, category, CategoryInput(text, text, null)).leftOrNull() },
        ),
        Triple(
            OperatorAction.WITHDRAW_CATEGORY,
            category.value,
            { WithdrawCategory(catalog)(it, category).leftOrNull() },
        ),
    )
}

class AdminPropertySpec :
    FunSpec({
        test("every operator capability refuses any caller without the operator role, records it and changes nothing") {
            checkAll(
                CatalogArbs.shelf,
                CatalogArbs.nonOperator,
                Arb.string(0..130),
                Arb.int(),
            ) { spec, caller, text, n ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val products = harness.products.products.toMap()
                val categories = harness.categories.categories.toMap()
                val levels = harness.inventory.levels.toMap()

                attempts(harness, shelf, text, n).forEach { (action, target, attempt) ->
                    attempt(caller) shouldBe CatalogError.Forbidden(action.operationId)
                    with(harness.audit.entries.last()) {
                        outcome shouldBe AuditOutcome.REFUSED
                        this.action shouldBe action
                        actorId shouldBe caller.accountId
                        targetId shouldBe target
                        at shouldBe NOW
                    }
                }

                harness.audit.entries.size shouldBe OperatorAction.entries.size
                harness.audit.entries
                    .map { it.id }
                    .toSet()
                    .size shouldBe OperatorAction.entries.size
                harness.products.products shouldBe products
                harness.categories.categories shouldBe categories
                harness.inventory.levels shouldBe levels
                harness.adjustments.shouldBeEmpty()
            }
        }

        test("the operator check passes operators without recording anything and refuses everybody else") {
            checkAll(CatalogArbs.caller, Arb.uuid()) { caller, target ->
                val harness = Harness()
                val outcome = AuthorizeOperator(harness.catalog)(caller, OperatorAction.UPDATE_PRODUCT, target)
                if (caller.isOperator) {
                    outcome.value() shouldBe caller.accountId
                    harness.audit.entries.shouldBeEmpty()
                } else {
                    outcome.error() shouldBe CatalogError.Forbidden("updateProduct")
                    harness.audit.lines shouldContainExactly
                        listOf("refused:${caller.accountId?.value}:updateProduct:$target")
                }
            }
        }

        test("an adjustment applies any delta that keeps the available quantity within bounds, and only those") {
            checkAll(CatalogArbs.activeStock, CatalogArbs.delta, CatalogArbs.operator) { stock, delta, operator ->
                val harness = Harness()
                val product = harness.product(stock = stock.onHand, reserved = stock.reserved)

                val outcome = AdjustStock(harness.catalog)(operator, product.id, delta, "Stocktake")

                val onHandAfter = stock.onHand.toLong() + delta
                if (onHandAfter >= stock.reserved && onHandAfter <= StockLevel.MAX) {
                    val adjustment = outcome.value()
                    adjustment.previousAvailable shouldBe stock.available
                    adjustment.newAvailable shouldBe stock.available + delta
                    adjustment.actorId shouldBe operator.accountId
                    harness.level(product).onHand.toLong() shouldBe onHandAfter
                    harness.level(product).reserved shouldBe stock.reserved
                    harness.adjustments shouldContainExactly listOf(adjustment)
                    harness.audit.lines shouldContainExactly
                        listOf("changed:${operator.accountId?.value}:adjustStock:${product.id.value}")
                } else {
                    outcome.isLeft() shouldBe true
                    harness.level(product).onHand shouldBe stock.onHand
                    harness.adjustments.shouldBeEmpty()
                    harness.audit.entries.shouldBeEmpty()
                }
            }
        }

        test("a created product is visible to shoppers at once with its stock, unless its category is hidden") {
            checkAll(CatalogArbs.shelf, CatalogArbs.productName, Arb.long(1L..1_000_000L), Arb.int(0..500)) {
                spec,
                name,
                price,
                stock,
                ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val hidden = shelf.hidden(spec)
                shelf.categories.forEachIndexed { index, category ->
                    val input = ProductInput(name, null, price, BRL, category.id)
                    // The harness ids are sequential, so generated SKUs would clash: each product gets its own.
                    val outcome = CreateProduct(harness.catalog)(OPERATOR, input, stock, "SKU-$index")
                    if (category.id in hidden) {
                        outcome.error() shouldBe
                            CatalogError.Invalid(
                                nonEmptyListOf(FieldIssue("categoryId", "must be an active category")),
                            )
                    } else {
                        val created = outcome.value()
                        val seen = GetProduct(harness.catalog)(Caller.ANONYMOUS, created.product.id).value()
                        seen.inStock shouldBe (stock > 0)
                        seen.product shouldBe created.product
                        harness.level(created.product).onHand shouldBe stock
                    }
                }
                val createdIds = harness.audit.entries.map { it.targetId }
                createdIds.size shouldBe shelf.categories.count { it.id !in hidden }
                harness.audit.entries.all { it.outcome == AuditOutcome.PERFORMED } shouldBe true
            }
        }

        test(
            "withdrawing a category hides exactly its subtree and their products from shoppers, never from operators",
        ) {
            checkAll(CatalogArbs.shelf, Arb.int(0..Int.MAX_VALUE)) { spec, pick ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val target = shelf.categories[pick % shelf.categories.size]

                val outcome = WithdrawCategory(harness.catalog)(OPERATOR, target.id)

                val after = spec.copy(withdrawnCategories = spec.withdrawnCategories + shelf.categories.indexOf(target))
                if (target.isActive) {
                    outcome.value().status shouldBe CategoryStatus.WITHDRAWN
                    harness.audit.lines shouldContainExactly
                        listOf("changed:${OPERATOR_ID.value}:withdrawCategory:${target.id.value}")
                } else {
                    outcome.error() shouldBe CatalogError.CategoryAlreadyWithdrawn(target.id)
                    harness.audit.entries.shouldBeEmpty()
                }
                val everything = PageRequest.of(0, ALL).valid()
                ListProducts(harness.catalog)(SHOPPER, null, true, everything).items.map {
                    it.product.id
                } shouldContainExactlyInAnyOrder
                    shelf.onSale(after).map { it.id }
                ListProducts(harness.catalog)(OPERATOR, null, true, everything).totalItems shouldBe shelf.products.size
                ListCategories(
                    harness.catalog,
                )(SHOPPER, null, everything).items.map { it.id } shouldContainExactlyInAnyOrder
                    shelf.categories.map { it.id }.filterNot { it in shelf.hidden(after) }
                ListCategories(harness.catalog)(OPERATOR, null, everything).totalItems shouldBe shelf.categories.size
                shelf.products.forEach { product ->
                    val visible = product in shelf.onSale(after)
                    GetProduct(harness.catalog)(SHOPPER, product.id).isRight() shouldBe visible
                    GetProduct(harness.catalog)(OPERATOR, product.id).value().onSale shouldBe visible
                }
            }
        }

        test("a failed change leaves no audit entry behind (the entry shares the change's transaction)") {
            checkAll(CatalogArbs.activeStock, CatalogArbs.productName) { stock, name ->
                val harness = Harness()
                val product = harness.product(stock = stock.onHand, reserved = stock.reserved)
                harness.products.losingUpdates = 1

                val input = ProductInput(name, null, Harness.PRICE, BRL, product.details.categoryId)
                UpdateProduct(harness.catalog)(OPERATOR, product.id, input).error() shouldBe
                    CatalogError.ConcurrentUpdate

                harness.audit.entries.shouldBeEmpty()
                harness.transactions.rollbacks shouldBe 1
            }
        }
    })

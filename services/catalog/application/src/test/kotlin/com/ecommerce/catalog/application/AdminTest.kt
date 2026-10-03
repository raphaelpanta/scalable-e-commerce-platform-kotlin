package com.ecommerce.catalog.application

import arrow.core.nonEmptyListOf
import com.ecommerce.catalog.application.admin.AddProductImage
import com.ecommerce.catalog.application.admin.AdjustStock
import com.ecommerce.catalog.application.admin.CategoryInput
import com.ecommerce.catalog.application.admin.CreateCategory
import com.ecommerce.catalog.application.admin.CreateProduct
import com.ecommerce.catalog.application.admin.ProductInput
import com.ecommerce.catalog.application.admin.UpdateCategory
import com.ecommerce.catalog.application.admin.UpdateProduct
import com.ecommerce.catalog.application.admin.WithdrawCategory
import com.ecommerce.catalog.application.admin.WithdrawProduct
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.CategoryStatus
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.InventoryLevel
import com.ecommerce.catalog.domain.Money
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.SaleState
import com.ecommerce.catalog.domain.Sku
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

private fun input(
    categoryId: CategoryId,
    name: String = "Camping stove",
    priceMinor: Long = 12000,
    currency: String = BRL,
) = ProductInput(name, "Burns gas.", priceMinor, currency, categoryId)

class AdminTest :
    FunSpec({
        context("the operator role") {
            test("every operator capability refuses shoppers and anonymous callers and records the attempt") {
                val harness = Harness()
                val category = harness.category()
                val product = harness.product(categoryId = category.id)
                val catalog = harness.catalog
                val attempts =
                    listOf<suspend (Caller) -> CatalogError>(
                        { CreateProduct(catalog)(it, input(category.id), 5).error() },
                        { UpdateProduct(catalog)(it, product.id, input(category.id)).error() },
                        { WithdrawProduct(catalog)(it, product.id).error() },
                        { AdjustStock(catalog)(it, product.id, 1, "Delivery").error() },
                        {
                            AddProductImage(
                                catalog,
                            )(it, product.id, "https://cdn.example.test/a.jpg", null, true).error()
                        },
                        { CreateCategory(catalog)(it, CategoryInput("Outdoor", null, null)).error() },
                        { UpdateCategory(catalog)(it, category.id, CategoryInput("Outdoor", null, null)).error() },
                        { WithdrawCategory(catalog)(it, category.id).error() },
                    )
                val actions =
                    listOf(
                        "createProduct",
                        "updateProduct",
                        "withdrawProduct",
                        "adjustStock",
                        "addProductImage",
                        "createCategory",
                        "updateCategory",
                        "withdrawCategory",
                    )

                attempts.zip(actions).forEach { (attempt, action) ->
                    attempt(SHOPPER) shouldBe CatalogError.Forbidden(action)
                    attempt(Caller.ANONYMOUS) shouldBe CatalogError.Forbidden(action)
                }

                harness.audit.lines.first() shouldBe "refused:${SHOPPER.accountId?.value}:createProduct:null"
                harness.audit.lines[2] shouldBe "refused:${SHOPPER.accountId?.value}:updateProduct:${product.id.value}"
                harness.audit.lines[3] shouldBe "refused:null:updateProduct:${product.id.value}"
                harness.audit.lines.size shouldBe actions.size * 2
                harness.products.products.values
                    .single() shouldBe product
                harness.level(product).onHand shouldBe 10
            }
        }

        context("products") {
            test("an operator creates an active product with its stock, visible at once") {
                val harness = Harness()
                val category = harness.category()

                val created = CreateProduct(harness.catalog)(OPERATOR, input(category.id), 5).value()

                created.showQuantity shouldBe true
                created.available shouldBe 5
                created.inStock shouldBe true
                with(created.product) {
                    id shouldBe ProductId(UUID(0L, 1L))
                    sku shouldBe Sku.generatedFor(id)
                    details.name.value shouldBe "Camping stove"
                    details.price shouldBe Money(12000, BRL)
                    saleState shouldBe SaleState.ACTIVE
                    createdAt shouldBe NOW
                }
                harness.products.products.values
                    .single() shouldBe created.product
                harness.inventory.levels.values
                    .single() shouldBe InventoryLevel(created.product.id, 5, 0)
                harness.audit.lines shouldContainExactly
                    listOf("changed:${OPERATOR_ID.value}:createProduct:${created.product.id.value}")
            }

            test("a given SKU is kept and must be unique") {
                val harness = Harness()
                val category = harness.category()
                CreateProduct(
                    harness.catalog,
                )(OPERATOR, input(category.id), 0, "STV-01").value().product.sku.value shouldBe
                    "STV-01"
                CreateProduct(harness.catalog)(OPERATOR, input(category.id), 0, "STV-01").error() shouldBe
                    CatalogError.DuplicateSku(Sku.of("STV-01").valid())
                harness.inventory.levels.size shouldBe 1
            }

            test("every invalid field is reported at once, including a foreign currency") {
                val harness = Harness()
                val category = harness.category()
                val refused =
                    CreateProduct(harness.catalog)(OPERATOR, input(category.id, "", 0, "USD"), -1, "bad sku")
                        .error()
                        .shouldBeInstanceOf<CatalogError.Invalid>()
                refused.issues.map { it.field } shouldContainExactly
                    listOf("name", "price.amountMinor", "price.currency", "initialStock", "sku")
                UpdateProduct(
                    harness.catalog,
                )(OPERATOR, ProductId(UUID.randomUUID()), input(category.id, currency = "EUR"))
                    .error()
                    .shouldBeInstanceOf<CatalogError.Invalid>()
                    .issues
                    .single()
                    .reason shouldBe "must be BRL"
                harness.products.products.values
                    .shouldBeEmpty()
            }

            test("the category must exist") {
                val harness = Harness()
                val unknown = CategoryId(UUID.randomUUID())
                CreateProduct(harness.catalog)(OPERATOR, input(unknown), 1).error() shouldBe
                    CatalogError.CategoryNotFound(unknown)
                val product = harness.product()
                UpdateProduct(harness.catalog)(OPERATOR, product.id, input(unknown)).error() shouldBe
                    CatalogError.CategoryNotFound(unknown)
            }

            test("an update replaces the details and keeps the stock") {
                val harness = Harness()
                val product = harness.product(stock = 4, reserved = 1)
                val moved = harness.category("Outdoor")

                val updated =
                    UpdateProduct(
                        harness.catalog,
                    )(OPERATOR, product.id, input(moved.id, "Stove", 9900)).value()

                updated.product.details.name.value shouldBe "Stove"
                updated.product.details.categoryId shouldBe moved.id
                updated.product.version shouldBe product.version + 1
                updated.available shouldBe 3
                harness.products.products.getValue(product.id) shouldBe updated.product
                harness.audit.lines.single() shouldBe "changed:${OPERATOR_ID.value}:updateProduct:${product.id.value}"
            }

            test("updating or withdrawing an unknown product is not found; a lost race is a conflict") {
                val harness = Harness()
                val unknown = ProductId(UUID.randomUUID())
                val category = harness.category()
                UpdateProduct(harness.catalog)(OPERATOR, unknown, input(category.id)).error() shouldBe
                    CatalogError.ProductNotFound(unknown)
                WithdrawProduct(harness.catalog)(OPERATOR, unknown).error() shouldBe
                    CatalogError.ProductNotFound(unknown)
                val product = harness.product(categoryId = category.id)
                harness.products.losingUpdates = 1
                UpdateProduct(harness.catalog)(OPERATOR, product.id, input(category.id)).error() shouldBe
                    CatalogError.ConcurrentUpdate
                harness.audit.lines.shouldBeEmpty()
            }

            test("withdrawing hides the product once; withdrawing again is a conflict") {
                val harness = Harness()
                val product = harness.product(stock = 3)

                val withdrawn = WithdrawProduct(harness.catalog)(OPERATOR, product.id).value()

                withdrawn.product.saleState shouldBe SaleState.WITHDRAWN
                withdrawn.available shouldBe 3
                withdrawn.inStock shouldBe false
                harness.products.products
                    .getValue(product.id)
                    .saleState shouldBe SaleState.WITHDRAWN
                WithdrawProduct(harness.catalog)(OPERATOR, product.id).error() shouldBe
                    CatalogError.AlreadyWithdrawn(product.id)
                GetProduct(harness.catalog)(SHOPPER, product.id).error() shouldBe
                    CatalogError.ProductNotFound(product.id)
            }

            test("an image is registered; the first one is primary and at most ten are kept") {
                val harness = Harness()
                val product = harness.product()

                val image =
                    AddProductImage(
                        harness.catalog,
                    )(OPERATOR, product.id, "https://cdn.example.test/a.jpg", " Side ", false)
                        .value()

                image.primary shouldBe true
                image.altText shouldBe "Side"
                image.ref.value shouldBe "https://cdn.example.test/a.jpg"
                harness.products.products
                    .getValue(product.id)
                    .images shouldContainExactly listOf(image)
                repeat(Product.MAX_IMAGES - 1) {
                    AddProductImage(harness.catalog)(OPERATOR, product.id, "img/$it.jpg", null, false).value()
                }
                AddProductImage(harness.catalog)(OPERATOR, product.id, "img/x.jpg", null, true).error() shouldBe
                    CatalogError.TooManyImages(product.id)
            }

            test("an image needs a valid reference and alternative text, on a known product") {
                val harness = Harness()
                val unknown = ProductId(UUID.randomUUID())
                AddProductImage(harness.catalog)(OPERATOR, unknown, "http://x", "a".repeat(201), false)
                    .error()
                    .shouldBeInstanceOf<CatalogError.Invalid>()
                    .issues
                    .map { it.field } shouldContainExactly listOf("url", "altText")
                AddProductImage(harness.catalog)(OPERATOR, unknown, "img/a.jpg", null, false).error() shouldBe
                    CatalogError.ProductNotFound(unknown)
            }
        }

        context("stock adjustments") {
            test("an adjustment applies the delta and records who, when and why") {
                val harness = Harness()
                val product = harness.product(stock = 10, reserved = 2)

                val adjustment =
                    AdjustStock(
                        harness.catalog,
                    )(OPERATOR, product.id, -3, " Damaged during stocktake ").value()

                with(adjustment) {
                    productId shouldBe product.id
                    delta shouldBe -3
                    reason.value shouldBe "Damaged during stocktake"
                    actorId shouldBe OPERATOR_ID
                    at shouldBe NOW
                    previousAvailable shouldBe 8
                    newAvailable shouldBe 5
                }
                harness.level(product) shouldBe InventoryLevel(product.id, 7, 2, 1)
                harness.adjustments shouldContainExactly listOf(adjustment)
                harness.audit.lines.single() shouldBe "changed:${OPERATOR_ID.value}:adjustStock:${product.id.value}"
            }

            test("an adjustment that would take the available quantity below zero is refused") {
                val harness = Harness()
                val product = harness.product(stock = 10, reserved = 8)

                AdjustStock(harness.catalog)(OPERATOR, product.id, -3, "Stocktake").error() shouldBe
                    CatalogError.StockBelowReserved(product.id, -3, 2)
                harness.level(product).onHand shouldBe 10
                harness.adjustments.shouldBeEmpty()
            }

            test("a concurrent reservation that makes the guarded update fail rolls the adjustment back") {
                val harness = Harness()
                val product = harness.product(stock = 10, reserved = 2)
                harness.inventory.failingAdjustments = 1

                AdjustStock(harness.catalog)(OPERATOR, product.id, -8, "Stocktake").error() shouldBe
                    CatalogError.StockBelowReserved(product.id, -8, 8)
                harness.adjustments.shouldBeEmpty()
            }

            test("the delta and the reason are mandatory and reported together; the product must exist") {
                val harness = Harness()
                val product = harness.product()
                AdjustStock(harness.catalog)(OPERATOR, product.id, 0, null)
                    .error()
                    .shouldBeInstanceOf<CatalogError.Invalid>()
                    .issues
                    .map { it.field } shouldContainExactly listOf("delta", "reason")
                val unknown = ProductId(UUID.randomUUID())
                AdjustStock(harness.catalog)(OPERATOR, unknown, 1, "Delivery").error() shouldBe
                    CatalogError.ProductNotFound(unknown)
                harness.inventory.levels.clear()
                AdjustStock(harness.catalog)(OPERATOR, product.id, 1, "Delivery").error() shouldBe
                    CatalogError.ProductNotFound(product.id)
            }
        }

        context("categories") {
            test("an operator creates a root or nested category; names are unique per parent") {
                val harness = Harness()

                val root = CreateCategory(harness.catalog)(OPERATOR, CategoryInput(" Outdoor ", "Tents", null)).value()
                val child = CreateCategory(harness.catalog)(OPERATOR, CategoryInput("Tents", null, root.id)).value()

                root.details.name.value shouldBe "Outdoor"
                root.details.description?.value shouldBe "Tents"
                root.createdAt shouldBe NOW
                child.details.parentId shouldBe root.id
                harness.categories.categories.values shouldContainExactly listOf(root, child)
                CreateCategory(harness.catalog)(OPERATOR, CategoryInput("outdoor", null, null))
                    .error()
                    .shouldBeInstanceOf<CatalogError.DuplicateCategoryName>()
                CreateCategory(harness.catalog)(OPERATOR, CategoryInput("Outdoor", null, root.id)).value()
                harness.audit.lines.first() shouldBe "changed:${OPERATOR_ID.value}:createCategory:${root.id.value}"
            }

            test("a category needs a valid name and an existing parent within four levels") {
                val harness = Harness()
                CreateCategory(harness.catalog)(OPERATOR, CategoryInput(" ", null, null))
                    .error()
                    .shouldBeInstanceOf<CatalogError.Invalid>()
                val unknown = CategoryId(UUID.randomUUID())
                CreateCategory(harness.catalog)(OPERATOR, CategoryInput("Tents", null, unknown)).error() shouldBe
                    CatalogError.CategoryNotFound(unknown)
                val first = harness.category("1")
                val second = harness.category("2", first.id)
                val third = harness.category("3", second.id)
                val fourth = harness.category("4", third.id)
                CreateCategory(harness.catalog)(OPERATOR, CategoryInput("5", null, fourth.id))
                    .error()
                    .shouldBeInstanceOf<CatalogError.InvalidHierarchy>()
            }

            test("an update renames or moves a category, never beneath its own descendants") {
                val harness = Harness()
                val outdoor = harness.category("Outdoor")
                val tents = harness.category("Tents", outdoor.id)
                val footwear = harness.category("Footwear")

                val moved =
                    UpdateCategory(
                        harness.catalog,
                    )(OPERATOR, tents.id, CategoryInput("Tents", null, footwear.id)).value()

                moved.details.parentId shouldBe footwear.id
                moved.version shouldBe tents.version + 1
                harness.categories.categories.getValue(tents.id) shouldBe moved
                UpdateCategory(
                    harness.catalog,
                )(OPERATOR, footwear.id, CategoryInput("Footwear", null, tents.id))
                    .error()
                    .shouldBeInstanceOf<CatalogError.InvalidHierarchy>()
                UpdateCategory(harness.catalog)(OPERATOR, outdoor.id, CategoryInput("Footwear", null, null))
                    .error()
                    .shouldBeInstanceOf<CatalogError.DuplicateCategoryName>()
                val unknown = CategoryId(UUID.randomUUID())
                UpdateCategory(harness.catalog)(OPERATOR, unknown, CategoryInput("X", null, null)).error() shouldBe
                    CatalogError.CategoryNotFound(unknown)
                UpdateCategory(harness.catalog)(OPERATOR, unknown, CategoryInput("", null, null))
                    .error()
                    .shouldBeInstanceOf<CatalogError.Invalid>()
            }

            test("withdrawing a category hides it, its descendants and their products; again is a conflict") {
                val harness = Harness()
                val outdoor = harness.category("Outdoor")
                val tents = harness.category("Tents", outdoor.id)
                val kitchen = harness.category("Kitchen")
                val tent = harness.product("Tent", categoryId = tents.id)
                val pan = harness.product("Pan", categoryId = kitchen.id)

                val withdrawn = WithdrawCategory(harness.catalog)(OPERATOR, outdoor.id).value()

                withdrawn.status shouldBe CategoryStatus.WITHDRAWN
                withdrawn.version shouldBe outdoor.version + 1
                withdrawn.updatedAt shouldBe NOW
                harness.categories.categories.getValue(outdoor.id) shouldBe withdrawn
                harness.audit.lines shouldContainExactly
                    listOf("changed:${OPERATOR_ID.value}:withdrawCategory:${outdoor.id.value}")
                WithdrawCategory(harness.catalog)(OPERATOR, outdoor.id).error() shouldBe
                    CatalogError.CategoryAlreadyWithdrawn(outdoor.id)
                val unknown = CategoryId(UUID.randomUUID())
                WithdrawCategory(harness.catalog)(OPERATOR, unknown).error() shouldBe
                    CatalogError.CategoryNotFound(unknown)
                GetProduct(harness.catalog)(SHOPPER, tent.id).error() shouldBe CatalogError.ProductNotFound(tent.id)
                GetProduct(harness.catalog)(SHOPPER, pan.id).value().inStock shouldBe true
                with(GetProduct(harness.catalog)(OPERATOR, tent.id).value()) {
                    onSale shouldBe false
                    inStock shouldBe false
                    product.saleState shouldBe SaleState.ACTIVE
                }
                GetCategory(harness.catalog)(SHOPPER, tents.id).error() shouldBe CatalogError.CategoryNotFound(tents.id)
                GetCategory(harness.catalog)(OPERATOR, tents.id).value() shouldBe tents
                GetPricing(harness.catalog)(tent.id).value().saleState shouldBe SaleState.WITHDRAWN
                ReserveStock(harness.catalog)(OrderId(UUID.randomUUID()), listOf(tent.id to 1))
                    .error()
                    .shouldBeInstanceOf<CatalogError.InsufficientStock>()
            }

            test("no product is created in, or moved into, a withdrawn category or one beneath it") {
                val harness = Harness()
                val outdoor = harness.category("Outdoor", status = CategoryStatus.WITHDRAWN)
                val tents = harness.category("Tents", outdoor.id)
                val product = harness.product()
                val refusal =
                    CatalogError.Invalid(
                        nonEmptyListOf(
                            FieldIssue("categoryId", "must be an active category"),
                        ),
                    )

                CreateProduct(harness.catalog)(OPERATOR, input(outdoor.id), 1).error() shouldBe refusal
                CreateProduct(harness.catalog)(OPERATOR, input(tents.id), 1).error() shouldBe refusal
                UpdateProduct(harness.catalog)(OPERATOR, product.id, input(tents.id)).error() shouldBe refusal
                harness.products.products.values
                    .single() shouldBe product
                harness.audit.entries.shouldBeEmpty()
            }

            test("audit entries carry a fresh id, the actor, the action, the target, the outcome and the time") {
                val harness = Harness()
                val category = harness.category()

                WithdrawCategory(harness.catalog)(OPERATOR, category.id).value()
                WithdrawCategory(harness.catalog)(SHOPPER, category.id).error()

                harness.audit.entries shouldContainExactly
                    listOf(
                        AuditEntry(
                            UUID(0L, 1L),
                            OPERATOR_ID,
                            OperatorAction.WITHDRAW_CATEGORY,
                            category.id.value,
                            AuditOutcome.PERFORMED,
                            NOW,
                        ),
                        AuditEntry(
                            UUID(0L, 2L),
                            SHOPPER.accountId,
                            OperatorAction.WITHDRAW_CATEGORY,
                            category.id.value,
                            AuditOutcome.REFUSED,
                            NOW,
                        ),
                    )
                OperatorAction.entries.map { it.target } shouldContainExactly
                    List(5) { AuditTarget.PRODUCT } + List(3) { AuditTarget.CATEGORY }
            }

            test("nothing withdrawn: the category tree is not even read to find hidden categories") {
                val harness = Harness()
                harness.product()
                harness.catalog.hiddenCategories().shouldBeEmpty()
                harness.categories.hierarchyReads shouldBe 0
                harness.category("Gone", status = CategoryStatus.WITHDRAWN)
                harness.catalog.hiddenCategories().size shouldBe 1
                harness.categories.hierarchyReads shouldBe 1
            }

            test("a category that changed meanwhile is a conflict") {
                val harness = Harness()
                val outdoor = harness.category("Outdoor")
                val stale =
                    object : CategoryRepository by harness.categories {
                        override suspend fun update(
                            category: com.ecommerce.catalog.domain.Category,
                            expectedVersion: Long,
                        ): WriteResult = WriteResult.STALE
                    }
                val catalog =
                    Catalog(
                        harness.products,
                        stale,
                        harness.inventory,
                        harness.reservations,
                        harness.catalog.adjustments,
                        harness.events,
                        harness.transactions,
                        harness.audit,
                        harness.catalog.settings,
                    )
                UpdateCategory(catalog)(OPERATOR, outdoor.id, CategoryInput("Camping", null, null)).error() shouldBe
                    CatalogError.ConcurrentUpdate
                WithdrawCategory(catalog)(OPERATOR, outdoor.id).error() shouldBe CatalogError.ConcurrentUpdate
                harness.audit.entries.shouldBeEmpty()
            }
        }
    })

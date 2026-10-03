package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.SearchTerm
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import java.util.Locale

/** Every page of a listing, from page 0 until a page comes back short. */
private suspend fun pages(
    size: Int,
    fetch: suspend (PageRequest) -> Page<ProductView>,
): List<Page<ProductView>> {
    val pages = mutableListOf(fetch(PageRequest.of(0, size).valid()))
    while (pages.last().items.size == size) pages += fetch(PageRequest.of(pages.size, size).valid())
    return pages
}

private fun Product.lowerName(): String = details.name.value.lowercase(Locale.ROOT)

class QueriesPropertySpec :
    FunSpec({
        test("paging walks every visible product exactly once, by name, with a stable total") {
            checkAll(CatalogArbs.shelf, CatalogArbs.caller, CatalogArbs.pageSize, Arb.element(true, false)) {
                spec,
                caller,
                size,
                includeWithdrawn,
                ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val expected =
                    if (caller.isOperator && includeWithdrawn) shelf.products else shelf.onSale(spec)

                val pages = pages(size) { ListProducts(harness.catalog)(caller, null, includeWithdrawn, it) }

                val listed = pages.flatMap { it.items }
                listed.map { it.product.id } shouldContainExactlyInAnyOrder expected.map { it.id }
                listed.map { it.product.lowerName() } shouldBeSortedWith naturalOrder()
                pages.forEachIndexed { index, page ->
                    page.page shouldBe index
                    page.size shouldBe size
                    page.items.size shouldBeLessThanOrEqual size
                    page.totalItems shouldBe expected.size.toLong()
                }
                listed.forEach { view ->
                    view.showQuantity shouldBe caller.isOperator
                    view.available shouldBe harness.level(view.product).available
                    view.inStock shouldBe (view.product in shelf.onSale(spec) && view.available > 0)
                }
            }
        }

        test("a category filter lists that category and its descendants only") {
            checkAll(CatalogArbs.shelf, Arb.element(0, 1, 2, 3, 4, 5)) { spec, pick ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val root = shelf.categories[pick % shelf.categories.size]
                val subtree = harness.categories.subtree(root.id)

                val listed = ListProducts(harness.catalog)(OPERATOR, root.id, true, PageRequest.of(0, 100).valid())

                listed.items.map { it.product.id } shouldContainExactlyInAnyOrder
                    shelf.products.filter { it.details.categoryId in subtree }.map { it.id }
            }
        }

        test("a search finds every visible product matching the term, best matches first, and nothing else") {
            checkAll(CatalogArbs.shelf, CatalogArbs.productName, CatalogArbs.caller) { spec, query, caller ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val term = SearchTerm.of(query).valid()
                val visible = if (caller.isOperator) shelf.products else shelf.onSale(spec)
                val matching =
                    visible.filter {
                        term.rank(
                            it.details.name.value,
                            it.details.description?.value,
                        ) != null
                    }

                val found =
                    SearchProducts(harness.catalog)(caller, query, null, true, PageRequest.of(0, 100).valid()).value()

                found.items.map { it.product.id } shouldContainExactlyInAnyOrder matching.map { it.id }
                found.totalItems shouldBe matching.size.toLong()
                found.items.map {
                    checkNotNull(
                        term.rank(
                            it.product.details.name.value,
                            it.product.details.description
                                ?.value,
                        ),
                    )
                } shouldBeSortedWith
                    naturalOrder()
            }
        }

        test("a blank or oversized search term is refused") {
            checkAll(Arb.element("", " ", "\t", "x".repeat(SearchTerm.MAX + 1))) { query ->
                SearchProducts(Harness().catalog)(SHOPPER, query, null, false, PageRequest.of().valid())
                    .error()
                    .shouldBeInstanceOf<CatalogError.Invalid>()
                    .issues
                    .map { it.field } shouldContainExactly listOf("q")
            }
        }

        test("pricing reports a product of a hidden category as withdrawn, whatever its own state") {
            checkAll(CatalogArbs.shelf) { spec ->
                val harness = Harness()
                val shelf = harness.shelve(spec)
                val onSale = shelf.onSale(spec)
                val batch =
                    GetPricingBatch(
                        harness.catalog,
                    )(shelf.products.map { it.id }.ifEmpty { listOf(Harness().product().id) })
                batch.value().forEach { pricing ->
                    pricing.saleState.name shouldBe if (pricing.product in onSale) "ACTIVE" else "WITHDRAWN"
                }
                shelf.products.forEach { product ->
                    GetPricing(harness.catalog)(product.id).value().saleState.name shouldBe
                        if (product in onSale) "ACTIVE" else "WITHDRAWN"
                }
            }
        }
    })

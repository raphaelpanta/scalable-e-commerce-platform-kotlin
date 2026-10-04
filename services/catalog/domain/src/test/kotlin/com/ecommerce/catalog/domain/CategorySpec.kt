package com.ecommerce.catalog.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.subsequence
import io.kotest.property.checkAll
import java.time.Instant

private val LATER: Instant = NOW.plusSeconds(5)

/** root <- child <- grandchild, and a second root with no children. */
private class Tree {
    val root = categoryId()
    val child = categoryId()
    val grandchild = categoryId()
    val other = categoryId()
    val parentOf: Map<CategoryId, CategoryId?> = mapOf(root to null, child to root, grandchild to child, other to null)
}

/**
 * A generated forest of 1..12 categories: each one is a root or sits beneath an earlier one, so there is no cycle,
 * with a subset of them withdrawn.
 */
private data class Forest(
    val parentOf: Map<CategoryId, CategoryId?>,
    val withdrawn: Set<CategoryId>,
)

private val forests: Arb<Forest> =
    Arb.int(1..12).flatMap { size ->
        Arb.list(Arb.int(-1..size), size..size).flatMap { parents ->
            val ids = List(size) { categoryId() }
            val parentOf =
                ids.mapIndexed { index, id -> id to parents[index].takeIf { it in 0 until index }?.let(ids::get) }
            Arb.subsequence(ids).map { withdrawn -> Forest(parentOf.toMap(), withdrawn.toSet()) }
        }
    }

class CategorySpec :
    FunSpec({
        test("category details validate the name and the shorter description, reporting every broken rule") {
            val parent = categoryId()
            val valid = CategoryDetails.of(" Footwear ", "Shoes and boots", parent).value()
            valid.name.value shouldBe "Footwear"
            valid.description?.value shouldBe "Shoes and boots"
            valid.parentId shouldBe parent
            val invalid = CategoryDetails.of("", "x".repeat(Description.CATEGORY_MAX + 1), null).error()
            invalid.issues.map { it.field } shouldContainExactly listOf("name", "description")
        }

        test("a category is created at version 0 and an update moves the version") {
            val created = Category.create(categoryId(), CategoryDetails.of("Outdoor", null, null).value(), NOW)
            created.createdAt shouldBe NOW
            created.updatedAt shouldBe NOW
            created.version shouldBe 0
            val updated = created.update(CategoryDetails.of("Camping", null, null).value(), LATER)
            updated.details.name.value shouldBe "Camping"
            updated.updatedAt shouldBe LATER
            updated.createdAt shouldBe NOW
            updated.version shouldBe 1
        }

        test("a category is created active; withdrawing moves the version once and is refused when repeated") {
            val created = Category.create(categoryId(), CategoryDetails.of("Outdoor", null, null).value(), NOW)
            created.status shouldBe CategoryStatus.ACTIVE
            created.isActive shouldBe true
            val withdrawn = created.withdraw(LATER).value()
            withdrawn.status shouldBe CategoryStatus.WITHDRAWN
            withdrawn.isActive shouldBe false
            withdrawn.updatedAt shouldBe LATER
            withdrawn.version shouldBe 1
            withdrawn.details shouldBe created.details
            withdrawn.withdraw(LATER).error() shouldBe CatalogError.CategoryAlreadyWithdrawn(created.id)
            withdrawn.update(CategoryDetails.of("Camping", null, null).value(), LATER).status shouldBe
                CategoryStatus.WITHDRAWN
        }

        test("reinstating a withdrawn category makes it active again; reinstating an active one is refused") {
            val created = Category.create(categoryId(), CategoryDetails.of("Outdoor", null, null).value(), NOW)
            created.reinstate(LATER).error() shouldBe CatalogError.CategoryNotWithdrawn(created.id)
            val withdrawn = created.withdraw(NOW).value()
            val reinstated = withdrawn.reinstate(LATER).value()
            reinstated.status shouldBe CategoryStatus.ACTIVE
            reinstated.isActive shouldBe true
            reinstated.updatedAt shouldBe LATER
            reinstated.createdAt shouldBe NOW
            reinstated.version shouldBe withdrawn.version + 1
            reinstated.details shouldBe created.details
            reinstated.reinstate(LATER).error() shouldBe CatalogError.CategoryNotWithdrawn(created.id)
        }

        test("reinstating shows a category again unless an ancestor is still withdrawn; a round trip is lossless") {
            checkAll(forests) { forest ->
                val before = CategoryTree.hidden(forest.withdrawn, forest.parentOf)
                forest.parentOf.keys.forEach { id ->
                    val category =
                        Category(
                            id,
                            CategoryDetails.of("C", null, forest.parentOf[id]).value(),
                            NOW,
                            NOW,
                            0,
                            if (id in forest.withdrawn) CategoryStatus.WITHDRAWN else CategoryStatus.ACTIVE,
                        )
                    if (category.isActive) {
                        val back =
                            category
                                .withdraw(LATER)
                                .value()
                                .reinstate(LATER)
                                .value()
                        back.status shouldBe CategoryStatus.ACTIVE
                        back shouldBe category.copy(updatedAt = LATER, version = category.version + 2)
                    } else {
                        category.reinstate(LATER).value().isActive shouldBe true
                        val after = CategoryTree.hidden(forest.withdrawn - id, forest.parentOf)
                        // Reinstating shows the category again unless an ancestor is still withdrawn.
                        (id in after) shouldBe
                            CategoryTree.ancestry(id, forest.parentOf).drop(1).any { it in forest.withdrawn }
                        after.all { it in before } shouldBe true
                    }
                }
            }
        }

        test("hidden categories are exactly the withdrawn ones and everything beneath them") {
            checkAll(forests) { forest ->
                val hidden = CategoryTree.hidden(forest.withdrawn, forest.parentOf)
                hidden shouldContainAll forest.withdrawn
                forest.parentOf.forEach { (id, parent) ->
                    // Independent characterisation: a category is hidden when withdrawn or when its parent is hidden.
                    (id in hidden) shouldBe (id in forest.withdrawn || (parent != null && parent in hidden))
                }
                CategoryTree.hidden(emptySet(), forest.parentOf).shouldBeEmpty()
                CategoryTree.hidden(forest.parentOf.keys, forest.parentOf) shouldBe forest.parentOf.keys
            }
        }

        test("a withdrawn id that is no longer a category hides nothing") {
            val tree = Tree()
            CategoryTree.hidden(setOf(categoryId()), tree.parentOf).shouldBeEmpty()
            CategoryTree.hidden(setOf(tree.child), tree.parentOf) shouldBe setOf(tree.child, tree.grandchild)
        }

        test("ancestry lists a category and its ancestors, nearest first, and survives cycles") {
            val tree = Tree()
            CategoryTree.ancestry(tree.grandchild, tree.parentOf) shouldContainExactly
                listOf(tree.grandchild, tree.child, tree.root)
            val a = categoryId()
            val b = categoryId()
            CategoryTree.ancestry(a, mapOf(a to b, b to a)) shouldContainExactly listOf(a, b)
        }

        test("height counts the levels of a subtree") {
            val tree = Tree()
            CategoryTree.height(tree.root, tree.parentOf) shouldBe 3
            CategoryTree.height(tree.child, tree.parentOf) shouldBe 2
            CategoryTree.height(tree.grandchild, tree.parentOf) shouldBe 1
            CategoryTree.height(tree.other, tree.parentOf) shouldBe 1
            val a = categoryId()
            CategoryTree.height(a, mapOf(a to a)) shouldBe 2
        }

        test("a new category may be a root or sit beneath an existing category within four levels") {
            val tree = Tree()
            CategoryTree.checkPlacement(null, null, tree.parentOf).value() shouldBe Unit
            CategoryTree.checkPlacement(null, tree.grandchild, tree.parentOf).value() shouldBe Unit
            val fourth = categoryId()
            val deeper = tree.parentOf + (fourth to tree.grandchild)
            CategoryTree
                .checkPlacement(null, fourth, deeper)
                .error()
                .shouldBeInstanceOf<CatalogError.InvalidHierarchy>()
            val unknown = categoryId()
            CategoryTree.checkPlacement(null, unknown, tree.parentOf).error() shouldBe
                CatalogError.CategoryNotFound(unknown)
        }

        test("a category cannot move beneath itself or one of its descendants") {
            val tree = Tree()
            CategoryTree.checkPlacement(tree.root, tree.grandchild, tree.parentOf).error() shouldBe
                CatalogError.InvalidHierarchy("a category cannot be moved beneath itself or one of its descendants")
            CategoryTree
                .checkPlacement(tree.child, tree.child, tree.parentOf)
                .error()
                .shouldBeInstanceOf<CatalogError.InvalidHierarchy>()
            CategoryTree.checkPlacement(tree.grandchild, tree.root, tree.parentOf).value() shouldBe Unit
            CategoryTree.checkPlacement(tree.child, null, tree.parentOf).value() shouldBe Unit
        }

        test("moving a subtree keeps every branch within four levels") {
            val tree = Tree()
            // root (3 levels) beneath other: 1 + 3 = 4 levels.
            CategoryTree.checkPlacement(tree.root, tree.other, tree.parentOf).value() shouldBe Unit
            val deep = categoryId()
            val withDeep = tree.parentOf + (deep to tree.other)
            // root (3 levels) beneath deep (depth 2): 2 + 3 = 5 levels.
            CategoryTree.checkPlacement(tree.root, deep, withDeep).error() shouldBe
                CatalogError.InvalidHierarchy("categories nest at most 4 levels deep")
        }
    })

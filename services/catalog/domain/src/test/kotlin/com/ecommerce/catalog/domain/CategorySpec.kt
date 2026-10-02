package com.ecommerce.catalog.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

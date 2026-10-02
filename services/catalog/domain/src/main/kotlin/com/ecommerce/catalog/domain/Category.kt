package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

/** What an operator sets on a category (catalog.yaml `CategoryInput`). */
data class CategoryDetails(
    val name: CategoryName,
    val description: Description?,
    val parentId: CategoryId?,
) {
    companion object {
        /** Validates the raw details, reporting every broken rule. */
        fun of(
            name: String,
            description: String?,
            parentId: CategoryId?,
        ): Either<CatalogError.Invalid, CategoryDetails> =
            Either
                .zipOrAccumulate(
                    CategoryName.of(name),
                    Description.of(description, Description.CATEGORY_MAX),
                ) { validName, validDescription -> CategoryDetails(validName, validDescription, parentId) }
                .mapLeft(CatalogError::Invalid)
    }
}

/** A category of the catalogue; nesting is expressed by [CategoryDetails.parentId] (data-model section 3.2). */
data class Category(
    val id: CategoryId,
    val details: CategoryDetails,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    /** The category with new [details]. */
    fun update(
        details: CategoryDetails,
        at: Instant,
    ): Category = copy(details = details, updatedAt = at, version = version + 1)

    companion object {
        /** Nesting depth limit: a root category has depth 1. */
        const val MAX_DEPTH: Int = 4

        fun create(
            id: CategoryId,
            details: CategoryDetails,
            at: Instant,
        ): Category = Category(id, details, at, at, 0)
    }
}

/**
 * Placement rules of the category tree, given the parent of every category (`parentOf`, keys are every existing
 * category): the parent must exist, a category cannot sit beneath itself or one of its descendants, and no branch
 * may be deeper than [Category.MAX_DEPTH].
 */
object CategoryTree {
    /** Checks placing [categoryId] (null for a new category) beneath [parentId] (null for a root). */
    fun checkPlacement(
        categoryId: CategoryId?,
        parentId: CategoryId?,
        parentOf: Map<CategoryId, CategoryId?>,
    ): Either<CatalogError, Unit> =
        when {
            parentId == null -> {
                withinDepth(0, categoryId, parentOf)
            }

            parentId !in parentOf -> {
                CatalogError.CategoryNotFound(parentId).left()
            }

            else -> {
                val chain = ancestry(parentId, parentOf)
                if (categoryId != null && categoryId in chain) {
                    CatalogError.InvalidHierarchy(CYCLE).left()
                } else {
                    withinDepth(chain.size, categoryId, parentOf)
                }
            }
        }

    private const val CYCLE = "a category cannot be moved beneath itself or one of its descendants"

    /** [start] and its ancestors, nearest first; stops at a repeated id so corrupted data cannot loop. */
    fun ancestry(
        start: CategoryId,
        parentOf: Map<CategoryId, CategoryId?>,
    ): List<CategoryId> {
        val chain = mutableListOf<CategoryId>()
        var current: CategoryId? = start
        while (current != null && current !in chain) {
            chain += current
            current = parentOf[current]
        }
        return chain
    }

    /** Levels of the subtree rooted at [categoryId]: 1 for a leaf (bounded by the number of categories). */
    fun height(
        categoryId: CategoryId,
        parentOf: Map<CategoryId, CategoryId?>,
    ): Int = subtreeHeight(categoryId, parentOf, parentOf.size + 1)

    private fun subtreeHeight(
        categoryId: CategoryId,
        parentOf: Map<CategoryId, CategoryId?>,
        budget: Int,
    ): Int {
        val children = if (budget > 1) parentOf.filterValues { it == categoryId }.keys else emptySet()
        return 1 + (children.maxOfOrNull { subtreeHeight(it, parentOf, budget - 1) } ?: 0)
    }

    private fun withinDepth(
        parentDepth: Int,
        categoryId: CategoryId?,
        parentOf: Map<CategoryId, CategoryId?>,
    ): Either<CatalogError, Unit> {
        val levels = if (categoryId == null || categoryId !in parentOf) 1 else height(categoryId, parentOf)
        return if (parentDepth + levels <= Category.MAX_DEPTH) {
            Unit.right()
        } else {
            CatalogError.InvalidHierarchy("categories nest at most ${Category.MAX_DEPTH} levels deep").left()
        }
    }
}

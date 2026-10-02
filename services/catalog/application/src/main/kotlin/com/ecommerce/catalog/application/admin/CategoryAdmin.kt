package com.ecommerce.catalog.application.admin

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.application.Catalog
import com.ecommerce.catalog.application.WriteResult
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.CategoryDetails
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.CategoryTree

/** The raw fields of catalog.yaml `CategoryInput`. */
data class CategoryInput(
    val name: String,
    val description: String?,
    val parentId: CategoryId?,
)

/** The outcome of a category write as a business error, if any. */
private fun Raise<CatalogError>.written(
    result: WriteResult,
    details: CategoryDetails,
) {
    when (result) {
        WriteResult.WRITTEN -> Unit
        WriteResult.DUPLICATE -> raise(CatalogError.DuplicateCategoryName(details.name))
        WriteResult.STALE -> raise(CatalogError.ConcurrentUpdate)
    }
}

/** `createCategory` (operator only): a duplicate name under the same parent is refused; at most four levels deep. */
class CreateCategory(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        input: CategoryInput,
    ): Either<CatalogError, Category> =
        either {
            val actor = catalog.authorize(caller, ACTION, null).bind()
            val details = CategoryDetails.of(input.name, input.description, input.parentId).bind()
            CategoryTree.checkPlacement(null, details.parentId, catalog.categories.hierarchy()).bind()
            val category = Category.create(CategoryId(catalog.newId()), details, catalog.now())
            written(catalog.categories.insert(category), details)
            catalog.audit.changed(actor, ACTION, category.id.value)
            category
        }

    private companion object {
        const val ACTION = "createCategory"
    }
}

/** `updateCategory` (operator only): moving a category beneath itself or one of its descendants is refused. */
class UpdateCategory(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        categoryId: CategoryId,
        input: CategoryInput,
    ): Either<CatalogError, Category> =
        either {
            val actor = catalog.authorize(caller, ACTION, categoryId.value).bind()
            val details = CategoryDetails.of(input.name, input.description, input.parentId).bind()
            val category =
                ensureNotNull(catalog.categories.find(categoryId)) { CatalogError.CategoryNotFound(categoryId) }
            CategoryTree.checkPlacement(categoryId, details.parentId, catalog.categories.hierarchy()).bind()
            val updated = category.update(details, catalog.now())
            written(catalog.categories.update(updated, category.version), details)
            catalog.audit.changed(actor, ACTION, categoryId.value)
            updated
        }

    private companion object {
        const val ACTION = "updateCategory"
    }
}

package com.ecommerce.catalog.infrastructure.persistence

import com.ecommerce.catalog.application.CategoryRepository
import com.ecommerce.catalog.application.WriteResult
import com.ecommerce.catalog.domain.Category
import com.ecommerce.catalog.domain.CategoryDetails
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.CategoryName
import com.ecommerce.catalog.domain.Description
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.PageRequest
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import java.util.UUID

/**
 * Outbound adapter: categories in `categories` (V2__catalog_schema.sql). Names are unique per parent through a
 * unique index, so a duplicate is detected by the database even under concurrency.
 */
class R2dbcCategoryRepository(
    private val database: DatabaseClient,
) : CategoryRepository {
    override suspend fun find(id: CategoryId): Category? =
        database
            .sql("SELECT $COLUMNS FROM categories WHERE id = :id")
            .bind("id", id.value)
            .map(::categoryOf)
            .awaitOneOrNull()

    override suspend fun page(
        parentId: CategoryId?,
        request: PageRequest,
    ): Page<Category> {
        val where = if (parentId == null) "" else "WHERE parent_id = :parentId"
        val bindings = parentId?.let { mapOf("parentId" to it.value) }.orEmpty()
        val total =
            database
                .sql("SELECT count(*) AS total FROM categories $where")
                .bindAll(bindings)
                .map { row -> row.required<Long>("total") }
                .awaitOne()
        val items =
            database
                .sql("SELECT $COLUMNS FROM categories $where ORDER BY lower(name), id LIMIT :limit OFFSET :offset")
                .bindAll(bindings)
                .bind("limit", request.size)
                .bind("offset", request.offset)
                .map(::categoryOf)
                .flow()
                .toList()
        return Page(items, request.page, request.size, total)
    }

    override suspend fun hierarchy(): Map<CategoryId, CategoryId?> =
        database
            .sql("SELECT id, parent_id FROM categories")
            .map { row -> CategoryId(row.required("id")) to row.optional<UUID>("parent_id")?.let(::CategoryId) }
            .flow()
            .toList()
            .toMap()

    override suspend fun insert(category: Category): WriteResult {
        val inserted =
            database
                .sql(
                    "INSERT INTO categories (id, name, description, parent_id, created_at, updated_at, version) " +
                        "VALUES (:id, :name, :description, :parentId, :createdAt, :updatedAt, :version) " +
                        "ON CONFLICT DO NOTHING",
                ).bindCategory(category)
                .bind("createdAt", category.createdAt)
                .fetch()
                .awaitRowsUpdated()
        return if (inserted == 1L) WriteResult.WRITTEN else WriteResult.DUPLICATE
    }

    override suspend fun update(
        category: Category,
        expectedVersion: Long,
    ): WriteResult =
        try {
            val updated =
                database
                    .sql(
                        "UPDATE categories SET name = :name, description = :description, parent_id = :parentId, " +
                            "updated_at = :updatedAt, version = :version WHERE id = :id AND version = :expected",
                    ).bindCategory(category)
                    .bind("expected", expectedVersion)
                    .fetch()
                    .awaitRowsUpdated()
            if (updated == 1L) WriteResult.WRITTEN else WriteResult.STALE
        } catch (_: DataIntegrityViolationException) {
            WriteResult.DUPLICATE
        }

    private companion object {
        const val COLUMNS = "id, name, description, parent_id, created_at, updated_at, version"

        fun categoryOf(row: Readable): Category =
            Category(
                id = CategoryId(row.required("id")),
                details =
                    CategoryDetails(
                        CategoryName.of(row.required("name")).trusted("name"),
                        Description.of(row.optional("description"), Description.CATEGORY_MAX).trusted("description"),
                        row.optional<UUID>("parent_id")?.let(::CategoryId),
                    ),
                createdAt = row.required("created_at"),
                updatedAt = row.required("updated_at"),
                version = row.required("version"),
            )

        fun DatabaseClient.GenericExecuteSpec.bindCategory(category: Category): DatabaseClient.GenericExecuteSpec =
            bind("id", category.id.value)
                .bind("name", category.details.name.value)
                .bindNullable("description", category.details.description?.value, String::class.java)
                .bindNullable("parentId", category.details.parentId?.value, UUID::class.java)
                .bind("updatedAt", category.updatedAt)
                .bind("version", category.version)
    }
}

package com.ecommerce.catalog.infrastructure.persistence

import com.ecommerce.catalog.application.ProductFilter
import com.ecommerce.catalog.application.ProductRepository
import com.ecommerce.catalog.application.WriteResult
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.Description
import com.ecommerce.catalog.domain.ImageId
import com.ecommerce.catalog.domain.ImageRef
import com.ecommerce.catalog.domain.Money
import com.ecommerce.catalog.domain.Page
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductDetails
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.ProductImage
import com.ecommerce.catalog.domain.ProductName
import com.ecommerce.catalog.domain.SaleState
import com.ecommerce.catalog.domain.Sku
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

/**
 * Outbound adapter: products and their images in `products` and `product_images` (V2__catalog_schema.sql) over
 * R2DBC. A product and its images are written in one transaction, joining the caller's when there is one. Listings
 * filter by category subtree (recursive query), sale state and search term in SQL, so paging is exact; the search
 * ranks like `SearchTerm.rank` (whole name, name prefix, name, description), case-insensitively.
 */
class R2dbcProductRepository(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
) : ProductRepository {
    override suspend fun find(id: ProductId): Product? = findAll(listOf(id)).firstOrNull()

    override suspend fun findAll(ids: Collection<ProductId>): List<Product> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            withImages(
                database
                    .sql("SELECT $COLUMNS FROM products p WHERE p.id = ANY(:ids)")
                    .bind("ids", ids.map { it.value }.toTypedArray())
                    .map(::productRow)
                    .flow()
                    .toList(),
            )
        }

    override suspend fun page(
        filter: ProductFilter,
        request: PageRequest,
    ): Page<Product> {
        val (where, bindings) = conditions(filter)
        val total =
            database
                .sql("SELECT count(*) AS total FROM products p $where")
                .bindAll(bindings)
                .map { row -> row.required<Long>("total") }
                .awaitOne()
        val term = filter.term
        val order = if (term == null) "lower(p.name), p.id" else "$RANK, lower(p.name), p.id"
        val ranking = term?.let { mapOf("term" to it.value, "prefix" to escapeLike(it.value) + "%") }.orEmpty()
        val rows =
            database
                .sql("SELECT $COLUMNS FROM products p $where ORDER BY $order LIMIT :limit OFFSET :offset")
                .bindAll(bindings + ranking)
                .bind("limit", request.size)
                .bind("offset", request.offset)
                .map(::productRow)
                .flow()
                .toList()
        return Page(withImages(rows), request.page, request.size, total)
    }

    override suspend fun insert(product: Product): WriteResult =
        transactions.executeAndAwait {
            val inserted =
                database
                    .sql(
                        "INSERT INTO products (id, sku, name, description, price_minor, currency, category_id, " +
                            "sale_state, created_at, updated_at, version) VALUES (:id, :sku, :name, :description, " +
                            ":price, :currency, :categoryId, :saleState, :createdAt, :updatedAt, :version) " +
                            "ON CONFLICT (sku) DO NOTHING",
                    ).bindProduct(product)
                    .bind("createdAt", product.createdAt)
                    .fetch()
                    .awaitRowsUpdated()
            if (inserted == 1L) {
                insertImages(product)
                WriteResult.WRITTEN
            } else {
                WriteResult.DUPLICATE
            }
        }

    override suspend fun update(
        product: Product,
        expectedVersion: Long,
    ): WriteResult =
        transactions.executeAndAwait {
            val updated =
                database
                    .sql(
                        "UPDATE products SET sku = :sku, name = :name, description = :description, " +
                            "price_minor = :price, currency = :currency, category_id = :categoryId, " +
                            "sale_state = :saleState, updated_at = :updatedAt, version = :version " +
                            "WHERE id = :id AND version = :expected",
                    ).bindProduct(product)
                    .bind("expected", expectedVersion)
                    .fetch()
                    .awaitRowsUpdated()
            if (updated == 1L) {
                database
                    .sql("DELETE FROM product_images WHERE product_id = :id")
                    .bind("id", product.id.value)
                    .fetch()
                    .awaitRowsUpdated()
                insertImages(product)
                WriteResult.WRITTEN
            } else {
                WriteResult.STALE
            }
        }

    private suspend fun insertImages(product: Product) {
        product.images.forEachIndexed { position, image ->
            database
                .sql(
                    "INSERT INTO product_images (id, product_id, url, alt_text, is_primary, position) " +
                        "VALUES (:id, :productId, :url, :altText, :primary, :position)",
                ).bind("id", image.id.value)
                .bind("productId", product.id.value)
                .bind("url", image.ref.value)
                .bindNullable("altText", image.altText, String::class.java)
                .bind("primary", image.primary)
                .bind("position", position)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    private suspend fun withImages(rows: List<ProductRow>): List<Product> {
        if (rows.isEmpty()) return emptyList()
        val images =
            database
                .sql(
                    "SELECT id, product_id, url, alt_text, is_primary FROM product_images " +
                        "WHERE product_id = ANY(:ids) ORDER BY product_id, position",
                ).bind("ids", rows.map { it.id }.toTypedArray())
                .map { row -> row.required<UUID>("product_id") to imageOf(row) }
                .flow()
                .toList()
                .groupBy({ it.first }, { it.second })
        return rows.map { it.toProduct(images[it.id].orEmpty()) }
    }

    /** One `products` row before its images are attached. */
    @Suppress("LongParameterList") // the columns of the table
    private class ProductRow(
        val id: UUID,
        val sku: String,
        val name: String,
        val description: String?,
        val priceMinor: Long,
        val currency: String,
        val categoryId: UUID,
        val saleState: String,
        val createdAt: Instant,
        val updatedAt: Instant,
        val version: Long,
    ) {
        fun toProduct(images: List<ProductImage>): Product =
            Product(
                id = ProductId(id),
                sku = Sku.of(sku).trusted("sku"),
                details =
                    ProductDetails(
                        ProductName.of(name).trusted("name"),
                        Description.of(description).trusted("description"),
                        Money(priceMinor, currency),
                        CategoryId(categoryId),
                    ),
                saleState = enumOf<SaleState>(saleState),
                images = images,
                createdAt = createdAt,
                updatedAt = updatedAt,
                version = version,
            )
    }

    private companion object {
        const val COLUMNS =
            "p.id, p.sku, p.name, p.description, p.price_minor, p.currency, p.category_id, p.sale_state, " +
                "p.created_at, p.updated_at, p.version"

        /** Relevance of the search term (lower is better), mirroring `SearchTerm.rank`. */
        const val RANK =
            "CASE WHEN lower(p.name) = :term THEN 0 WHEN lower(p.name) LIKE :prefix ESCAPE '\\' THEN 1 " +
                "WHEN lower(p.name) LIKE :contains ESCAPE '\\' THEN 2 ELSE 3 END"

        const val SUBTREE =
            "p.category_id IN (WITH RECURSIVE scope(id) AS (SELECT CAST(:categoryId AS uuid) UNION ALL " +
                "SELECT c.id FROM categories c JOIN scope s ON c.parent_id = s.id) SELECT id FROM scope)"

        const val MATCHES =
            "(lower(p.name) LIKE :contains ESCAPE '\\' " +
                "OR lower(coalesce(p.description, '')) LIKE :contains ESCAPE '\\')"

        /** The WHERE clause of [filter] and its bindings. */
        fun conditions(filter: ProductFilter): Pair<String, Map<String, Any>> {
            val clauses = mutableListOf<String>()
            val bindings = mutableMapOf<String, Any>()
            if (!filter.includeWithdrawn) clauses += "p.sale_state = 'active'"
            filter.categoryId?.let {
                clauses += SUBTREE
                bindings["categoryId"] = it.value
            }
            filter.term?.let { term ->
                clauses += MATCHES
                bindings["contains"] = "%" + escapeLike(term.value) + "%"
            }
            val where = if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", prefix = "WHERE ")
            return where to bindings
        }

        /** [value] with the LIKE wildcards and the escape character escaped. */
        fun escapeLike(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        fun productRow(row: Readable): ProductRow =
            ProductRow(
                id = row.required("id"),
                sku = row.required("sku"),
                name = row.required("name"),
                description = row.optional("description"),
                priceMinor = row.required("price_minor"),
                currency = row.required("currency"),
                categoryId = row.required("category_id"),
                saleState = row.required("sale_state"),
                createdAt = row.required("created_at"),
                updatedAt = row.required("updated_at"),
                version = row.required("version"),
            )

        fun imageOf(row: Readable): ProductImage =
            ProductImage(
                id = ImageId(row.required("id")),
                ref = ImageRef.of(row.required("url")).trusted("url"),
                altText = row.optional("alt_text"),
                primary = row.required("is_primary"),
            )

        fun DatabaseClient.GenericExecuteSpec.bindProduct(product: Product): DatabaseClient.GenericExecuteSpec =
            bind("id", product.id.value)
                .bind("sku", product.sku.value)
                .bind("name", product.details.name.value)
                .bindNullable("description", product.details.description?.value, String::class.java)
                .bind("price", product.details.price.amountMinor)
                .bind("currency", product.details.price.currency)
                .bind("categoryId", product.details.categoryId.value)
                .bind("saleState", product.saleState.column())
                .bind("updatedAt", product.updatedAt)
                .bind("version", product.version)
    }
}

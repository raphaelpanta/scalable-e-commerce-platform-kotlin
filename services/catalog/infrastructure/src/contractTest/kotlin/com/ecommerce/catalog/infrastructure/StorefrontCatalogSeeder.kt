package com.ecommerce.catalog.infrastructure

import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The fixed identifiers and values of the storefront pact (`storefront-catalog.json`, pact-matrix.md C1 to C7). */
internal object StorefrontPact {
    val FOOTWEAR: UUID = UUID.fromString("5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45")
    val SHOES: UUID = UUID.fromString("0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21")
    val SHOES_IMAGE: UUID = UUID.fromString("91d3a0b2-7c4e-4f15-b6a8-2e0d5c1f3a67")
    val UNKNOWN: UUID = UUID.fromString("00000000-0000-4000-8000-000000000000")
    val ANA: UUID = UUID.fromString("7c1d4f3e-5a2b-4c96-8d10-3e9f6a1b2c47")
    val OPS: UUID = UUID.fromString("2e8b5a90-4d17-4f63-9c0a-7b1e3d5f8a26")
    val SEEDED_AT: Instant = Instant.parse("2026-10-02T08:00:00Z")

    const val SHOES_NAME = "Trail Running Shoes"
    const val SHOES_DESCRIPTION = "Lightweight shoes with a grippy sole."
    const val SHOES_PRICE_MINOR = 8990L
    const val SHOES_IMAGE_URL = "https://cdn.example.test/shoes.jpg"
    const val STOCK_IN = 5
    const val ACTIVE_PRODUCTS = 25
    const val WITHDRAWN_PRODUCTS = 2
    const val CATEGORY_PRODUCTS = 3
}

/** One product row with its stock and one primary image, as the storefront reads it. */
internal data class SeededProduct(
    val id: UUID,
    val sku: String,
    val name: String,
    val description: String = "A pact fixture product.",
    val priceMinor: Long = SEEDED_PRICE_MINOR,
    val categoryId: UUID = StorefrontPact.FOOTWEAR,
    val saleState: String = "active",
    val stock: Int = StorefrontPact.STOCK_IN,
) {
    companion object {
        const val SEEDED_PRICE_MINOR = 1500L

        /** The product of the C5 interactions, with [stock] units and the given [saleState]. */
        fun shoes(
            stock: Int,
            saleState: String = "active",
        ): SeededProduct =
            SeededProduct(
                id = StorefrontPact.SHOES,
                sku = "PACT-SHOES",
                name = StorefrontPact.SHOES_NAME,
                description = StorefrontPact.SHOES_DESCRIPTION,
                priceMinor = StorefrontPact.SHOES_PRICE_MINOR,
                saleState = saleState,
                stock = stock,
            )
    }
}

/** Writes the catalogue rows the storefront provider states describe; the tables are empty before each interaction. */
internal class StorefrontCatalogSeeder(
    private val database: DatabaseClient,
) {
    /** Removes every category (and so every product): the catalogue is empty. */
    fun clearCategories() {
        execute("TRUNCATE products, categories CASCADE")
    }

    /** The category [id], when it does not exist yet. */
    fun category(
        id: UUID,
        name: String,
        description: String,
    ) {
        execute(
            "INSERT INTO categories (id, name, description, created_at, updated_at, version, status) " +
                "VALUES (:id, :name, :description, :at, :at, 0, 'active') ON CONFLICT (id) DO NOTHING",
            mapOf("id" to id, "name" to name, "description" to description, "at" to StorefrontPact.SEEDED_AT),
        )
    }

    /** The category the pact reads everywhere (`Footwear`). */
    fun footwear() = category(StorefrontPact.FOOTWEAR, "Footwear", "Shoes and boots")

    /** [product] with its stock and its primary image; its category is created when missing. */
    fun product(product: SeededProduct) {
        if (product.categoryId == StorefrontPact.FOOTWEAR) footwear()
        execute(
            "INSERT INTO products (id, sku, name, description, price_minor, currency, category_id, sale_state, " +
                "created_at, updated_at, version) VALUES (:id, :sku, :name, :description, :price, 'BRL', " +
                ":category, :saleState, :at, :at, 0)",
            mapOf(
                "id" to product.id,
                "sku" to product.sku,
                "name" to product.name,
                "description" to product.description,
                "price" to product.priceMinor,
                "category" to product.categoryId,
                "saleState" to product.saleState,
                "at" to StorefrontPact.SEEDED_AT,
            ),
        )
        execute(
            "INSERT INTO inventory_levels (product_id, on_hand, reserved, version, updated_at) " +
                "VALUES (:id, :stock, 0, 0, :at)",
            mapOf("id" to product.id, "stock" to product.stock, "at" to StorefrontPact.SEEDED_AT),
        )
        execute(
            "INSERT INTO product_images (id, product_id, url, alt_text, is_primary, position) " +
                "VALUES (:imageId, :id, :url, 'Shoes', true, 0)",
            mapOf(
                "imageId" to imageId(product),
                "id" to product.id,
                "url" to StorefrontPact.SHOES_IMAGE_URL,
            ),
        )
    }

    /** [count] numbered products in [categoryId] in the given [saleState], each with stock. */
    fun numberedProducts(
        count: Int,
        categoryId: UUID = StorefrontPact.FOOTWEAR,
        saleState: String = "active",
        firstNumber: Int = 1,
    ) {
        for (number in firstNumber until firstNumber + count) {
            val digits = number.toString().padStart(NUMBER_DIGITS, '0')
            product(
                SeededProduct(
                    id = UUID.nameUUIDFromBytes("pact-product-$number".toByteArray()),
                    sku = "PACT-$digits",
                    name = "Pact product $digits",
                    categoryId = categoryId,
                    saleState = saleState,
                ),
            )
        }
    }

    private fun imageId(product: SeededProduct): UUID =
        if (product.id == StorefrontPact.SHOES) {
            StorefrontPact.SHOES_IMAGE
        } else {
            UUID.nameUUIDFromBytes("image-${product.id}".toByteArray())
        }

    private fun execute(
        sql: String,
        bindings: Map<String, Any> = emptyMap(),
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    private companion object {
        const val NUMBER_DIGITS = 4
        val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

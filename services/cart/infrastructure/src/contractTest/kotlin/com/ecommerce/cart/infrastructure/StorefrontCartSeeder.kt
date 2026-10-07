package com.ecommerce.cart.infrastructure

import com.ecommerce.platform.core.values.SecretToken
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One product of the stubbed catalogue; [priceMinor] and [available] are what the cart sees now. */
internal data class CatalogProduct(
    val id: UUID,
    val sku: String,
    val name: String,
    val priceMinor: Long,
    val available: Int = StorefrontPact.STOCK,
)

/** The fixed identifiers and values of the storefront pact (`storefront-cart.json`, pact-matrix.md K1 to K7). */
internal object StorefrontPact {
    const val TOKEN = "tok-cart-1"
    const val SHOES_PRICE_MINOR = 8990L
    const val SHOES_PRICE_AFTER_CHANGE_MINOR = 9490L
    const val SOCKS_PRICE_MINOR = 1990L
    const val STOCK = 5
    const val SHOES_QUANTITY = 2
    const val SOCKS_QUANTITY = 1
    const val ACCOUNT_SHOES_QUANTITY = 4

    val ANA: UUID = UUID.fromString("7c1d4f3e-5a2b-4c96-8d10-3e9f6a1b2c47")
    val ANONYMOUS_CART: UUID = UUID.fromString("8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34")
    val ACCOUNT_CART: UUID = UUID.fromString("3b9e7c52-0a14-4d86-b2f1-5c8d6e0a9f37")
    val SHOES_LINE: UUID = UUID.fromString("c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18")
    val SOCKS_LINE: UUID = UUID.fromString("f2b8d3a5-7c18-4e4b-9f62-1c8e5b0d3a29")
    val ACCOUNT_SHOES_LINE: UUID = UUID.fromString("a4d6f1c8-9e27-4b35-8c40-6e2a7d9b1f53")
    val SEEDED_AT: Instant = Instant.parse("2026-10-02T10:20:00Z")

    val SHOES =
        CatalogProduct(
            UUID.fromString("0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21"),
            "PACT-SHOES",
            "Trail Running Shoes",
            SHOES_PRICE_MINOR,
        )
    val SOCKS =
        CatalogProduct(
            UUID.fromString("6a3d9f12-8b45-4c07-a1e9-2d7c5b8e4f63"),
            "PACT-SOCKS",
            "Wool Socks",
            SOCKS_PRICE_MINOR,
        )
}

/**
 * The catalogue's internal pricing API on [server] for the storefront states: the products registered here answer
 * `getProductPricing` and `getProductsPricing`, any other id is a 404 `not-found` problem. Before each interaction it
 * holds the two products of the pact with stock to spare.
 */
internal class StorefrontCatalog(
    private val server: WireMockServer,
) {
    private val mapper = JsonMapper.builder().build()
    private val products = linkedMapOf<UUID, CatalogProduct>()

    /** Forgets every earlier stub and registers the pact's products with stock to spare. */
    fun reset() {
        server.resetAll()
        products.clear()
        stubUnknownProducts()
        register(StorefrontPact.SHOES)
        register(StorefrontPact.SOCKS)
    }

    /** Registers [product], replacing what the catalogue knew of its id. */
    fun register(product: CatalogProduct) {
        products[product.id] = product
        server.stubFor(
            get(urlEqualTo("/internal/products/${product.id}/pricing")).willReturn(json(OK, pricing(product))),
        )
        server.stubFor(
            post(urlEqualTo("/internal/products/pricing"))
                .willReturn(json(OK, mapOf("items" to products.values.map(::pricing)))),
        )
    }

    private fun stubUnknownProducts() {
        val problem =
            mapOf(
                "type" to "https://ecommerce.example/problems/not-found",
                "title" to "Not found",
                "status" to NOT_FOUND,
                "detail" to "Product not found.",
            )
        server.stubFor(
            get(urlPathMatching("/internal/products/.+/pricing"))
                .atPriority(LOW_PRIORITY)
                .willReturn(json(NOT_FOUND, problem).withHeader("Content-Type", "application/problem+json")),
        )
    }

    private fun pricing(product: CatalogProduct): Map<String, Any> =
        mapOf(
            "productId" to product.id.toString(),
            "sku" to product.sku,
            "name" to product.name,
            "price" to mapOf("amountMinor" to product.priceMinor, "currency" to "BRL"),
            "available" to product.available,
            "saleState" to "active",
        )

    private fun json(
        status: Int,
        body: Any,
    ) = aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body))

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val LOW_PRIORITY = 10
    }
}

/** A line of a seeded cart, priced at the product's price of the pact when it was added. */
internal data class SeededLine(
    val id: UUID,
    val product: CatalogProduct,
    val quantity: Int,
)

/** Writes the carts the storefront provider states describe. */
internal class StorefrontCartSeeder(
    private val database: DatabaseClient,
) {
    /** Removes every cart (their lines with them) and the trigger of an earlier "merge in progress". */
    fun reset() {
        execute("DROP TRIGGER IF EXISTS $MERGE_TRIGGER ON cart")
        execute("DELETE FROM cart")
    }

    /** The anonymous cart of `tok-cart-1` with two lines: [StorefrontPact.SHOES] and [StorefrontPact.SOCKS]. */
    fun anonymousCart() {
        execute(
            "INSERT INTO cart (id, token_hash, version, created_at, updated_at) VALUES (:id, :owner, 1, :at, :at)",
            mapOf("id" to StorefrontPact.ANONYMOUS_CART, "owner" to TOKEN_HASH, "at" to StorefrontPact.SEEDED_AT),
        )
        val shoes = SeededLine(StorefrontPact.SHOES_LINE, StorefrontPact.SHOES, StorefrontPact.SHOES_QUANTITY)
        val socks = SeededLine(StorefrontPact.SOCKS_LINE, StorefrontPact.SOCKS, StorefrontPact.SOCKS_QUANTITY)
        line(StorefrontPact.ANONYMOUS_CART, 0, shoes)
        line(StorefrontPact.ANONYMOUS_CART, 1, socks)
    }

    /** The account cart of Ana with one line of [StorefrontPact.SHOES] of [quantity] units. */
    fun accountCart(quantity: Int) {
        execute(
            "INSERT INTO cart (id, account_id, version, created_at, updated_at) VALUES (:id, :owner, 1, :at, :at)",
            mapOf("id" to StorefrontPact.ACCOUNT_CART, "owner" to StorefrontPact.ANA, "at" to StorefrontPact.SEEDED_AT),
        )
        line(
            StorefrontPact.ACCOUNT_CART,
            0,
            SeededLine(StorefrontPact.ACCOUNT_SHOES_LINE, StorefrontPact.SHOES, quantity),
        )
    }

    /**
     * Makes the delete that consumes the anonymous cart of `tok-cart-1` affect no row, as when another merge of the
     * same token won the race: the service then answers 409 and changes nothing.
     */
    fun mergeInProgress() {
        execute(
            "CREATE OR REPLACE FUNCTION $MERGE_TRIGGER() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN RETURN NULL; END \$\$",
        )
        execute(
            "CREATE TRIGGER $MERGE_TRIGGER BEFORE DELETE ON cart FOR EACH ROW " +
                "WHEN (OLD.token_hash = '$TOKEN_HASH') EXECUTE FUNCTION $MERGE_TRIGGER()",
        )
    }

    private fun line(
        cartId: UUID,
        position: Int,
        line: SeededLine,
    ) {
        execute(
            "INSERT INTO cart_line (id, cart_id, product_id, sku, name, quantity, price_at_add_minor, currency, " +
                "added_at, position) VALUES (:id, :cartId, :productId, :sku, :name, :quantity, :price, 'BRL', " +
                ":at, :position)",
            mapOf(
                "id" to line.id,
                "cartId" to cartId,
                "productId" to line.product.id,
                "sku" to line.product.sku,
                "name" to line.product.name,
                "quantity" to line.quantity,
                "price" to line.product.priceMinor,
                "at" to StorefrontPact.SEEDED_AT,
                "position" to position,
            ),
        )
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
        const val MERGE_TRIGGER = "pact_merge_in_progress"
        val TOKEN_HASH: String = SecretToken.sha256Hex(StorefrontPact.TOKEN)
        val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

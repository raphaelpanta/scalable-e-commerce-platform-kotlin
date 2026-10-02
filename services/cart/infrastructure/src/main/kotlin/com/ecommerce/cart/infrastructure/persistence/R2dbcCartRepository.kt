package com.ecommerce.cart.infrastructure.persistence

import com.ecommerce.cart.application.CartRepository
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.Cart
import com.ecommerce.cart.domain.CartId
import com.ecommerce.cart.domain.CartLine
import com.ecommerce.cart.domain.CartOwner
import com.ecommerce.cart.domain.LineId
import com.ecommerce.cart.domain.Money
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.Quantity
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

/**
 * Outbound adapter: carts in the `cart` and `cart_line` tables (V2__cart_schema.sql) over R2DBC. A cart and its
 * lines are written in one transaction, which joins the caller's transaction when there is one (merge, event
 * consumers). Updates and deletes are conditional on the version the caller read (optimistic locking).
 */
class R2dbcCartRepository(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
) : CartRepository {
    override suspend fun findByAccount(accountId: AccountId): Cart? = findOne("account_id", accountId.value)

    override suspend fun findByTokenHash(tokenHash: String): Cart? = findOne("token_hash", tokenHash)

    override suspend fun insert(cart: Cart): Boolean =
        try {
            transactions.executeAndAwait {
                val (accountId, tokenHash) = ownerColumns(cart.owner)
                database
                    .sql(
                        "INSERT INTO cart (id, account_id, token_hash, version, created_at, updated_at) " +
                            "VALUES (:id, :accountId, :tokenHash, :version, :now, :now)",
                    ).bind("id", cart.id.value)
                    .bindNullable("accountId", accountId, UUID::class.java)
                    .bindNullable("tokenHash", tokenHash, String::class.java)
                    .bind("version", cart.version)
                    .bind("now", cart.updatedAt)
                    .fetch()
                    .awaitRowsUpdated()
                insertLines(cart)
            }
            true
        } catch (_: DataIntegrityViolationException) {
            // The owner already has a cart: a concurrent first write won.
            false
        }

    override suspend fun update(
        cart: Cart,
        expectedVersion: Long,
    ): Boolean =
        transactions.executeAndAwait {
            val updated =
                database
                    .sql("UPDATE cart SET version = :version, updated_at = :now WHERE id = :id AND version = :expected")
                    .bind("version", cart.version)
                    .bind("now", cart.updatedAt)
                    .bind("id", cart.id.value)
                    .bind("expected", expectedVersion)
                    .fetch()
                    .awaitRowsUpdated()
            if (updated == 1L) {
                database
                    .sql("DELETE FROM cart_line WHERE cart_id = :id")
                    .bind("id", cart.id.value)
                    .fetch()
                    .awaitRowsUpdated()
                insertLines(cart)
            }
            updated == 1L
        }

    override suspend fun delete(cart: Cart): Boolean =
        database
            .sql("DELETE FROM cart WHERE id = :id AND version = :version")
            .bind("id", cart.id.value)
            .bind("version", cart.version)
            .fetch()
            .awaitRowsUpdated() == 1L

    override suspend fun deleteByAccount(accountId: AccountId): Boolean =
        database
            .sql("DELETE FROM cart WHERE account_id = :accountId")
            .bind("accountId", accountId.value)
            .fetch()
            .awaitRowsUpdated() > 0

    override suspend fun deleteAnonymousIdleSince(cutoff: Instant): Int =
        database
            .sql("DELETE FROM cart WHERE token_hash IS NOT NULL AND updated_at < :cutoff")
            .bind("cutoff", cutoff)
            .fetch()
            .awaitRowsUpdated()
            .toInt()

    private suspend fun findOne(
        ownerColumn: String,
        owner: Any,
    ): Cart? {
        val header =
            database
                .sql("SELECT id, account_id, token_hash, version, updated_at FROM cart WHERE $ownerColumn = :owner")
                .bind("owner", owner)
                .map { row -> CartHeader.of(row) }
                .awaitOneOrNull() ?: return null
        val lines =
            database
                .sql(
                    "SELECT id, product_id, sku, name, quantity, price_at_add_minor, currency, added_at " +
                        "FROM cart_line WHERE cart_id = :cartId ORDER BY position",
                ).bind("cartId", header.id)
                .map { row -> lineOf(row) }
                .flow()
                .toList()
        return Cart(CartId(header.id), header.owner, lines, header.version, header.updatedAt)
    }

    private suspend fun insertLines(cart: Cart) {
        cart.lines.forEachIndexed { position, line ->
            database
                .sql(
                    "INSERT INTO cart_line (id, cart_id, product_id, sku, name, quantity, price_at_add_minor, " +
                        "currency, added_at, position) VALUES (:id, :cartId, :productId, :sku, :name, :quantity, " +
                        ":price, :currency, :addedAt, :position)",
                ).bind("id", line.id.value)
                .bind("cartId", cart.id.value)
                .bind("productId", line.productId.value)
                .bind("sku", line.sku)
                .bind("name", line.name)
                .bind("quantity", line.quantity.value)
                .bind("price", line.priceAtAdd.amountMinor)
                .bind("currency", line.priceAtAdd.currency)
                .bind("addedAt", line.addedAt)
                .bind("position", position)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    private data class CartHeader(
        val id: UUID,
        val owner: CartOwner,
        val version: Long,
        val updatedAt: Instant,
    ) {
        companion object {
            fun of(row: Readable): CartHeader {
                val accountId = row.get("account_id", UUID::class.java)
                val owner =
                    if (accountId != null) {
                        CartOwner.Account(AccountId(accountId))
                    } else {
                        CartOwner.Anonymous(row.required("token_hash"))
                    }
                return CartHeader(row.required("id"), owner, row.required("version"), row.required("updated_at"))
            }
        }
    }

    private companion object {
        fun ownerColumns(owner: CartOwner): Pair<UUID?, String?> =
            when (owner) {
                is CartOwner.Account -> owner.accountId.value to null
                is CartOwner.Anonymous -> null to owner.tokenHash
            }

        fun lineOf(row: Readable): CartLine =
            CartLine(
                id = LineId(row.required("id")),
                productId = ProductId(row.required("product_id")),
                sku = row.required("sku"),
                name = row.required("name"),
                quantity = checkNotNull(Quantity.of(row.required<Int>("quantity")).getOrNull()) { "stored quantity" },
                priceAtAdd = Money(row.required("price_at_add_minor"), row.required("currency")),
                addedAt = row.required("added_at"),
            )

        inline fun <reified T : Any> Readable.required(name: String): T =
            checkNotNull(get(name, T::class.javaObjectType)) { "column $name is null" }

        fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
            name: String,
            value: T?,
            type: Class<T>,
        ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
    }
}

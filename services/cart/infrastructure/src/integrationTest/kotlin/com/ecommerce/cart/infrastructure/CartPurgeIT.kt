package com.ecommerce.cart.infrastructure

import com.ecommerce.cart.infrastructure.jobs.CartPurgeJob
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val IDLE_DAYS = 31L
private const val RECENT_DAYS = 29L

/** Anonymous carts idle for 30 days are purged; recent ones and account carts are kept (T040, T048). */
class CartPurgeIT(
    @Autowired private val purgeJob: CartPurgeJob,
) : CartIntegrationTest() {
    private fun insertCart(
        accountId: UUID?,
        tokenHash: String?,
        idleDays: Long,
    ): UUID {
        val id = UUID.randomUUID()
        val updatedAt = Instant.now().minus(Duration.ofDays(idleDays))
        database
            .sql(
                "INSERT INTO cart (id, account_id, token_hash, version, created_at, updated_at) " +
                    "VALUES (:id, :accountId, :tokenHash, 1, :at, :at)",
            ).bind("id", id)
            .let { spec ->
                accountId?.let { spec.bind("accountId", it) } ?: spec.bindNull("accountId", UUID::class.java)
            }.let { spec ->
                tokenHash?.let { spec.bind("tokenHash", it) }
                    ?: spec.bindNull("tokenHash", String::class.java)
            }.bind("at", updatedAt)
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
        database
            .sql(
                "INSERT INTO cart_line (id, cart_id, product_id, sku, name, quantity, price_at_add_minor, currency, " +
                    "added_at, position) VALUES (:id, :cartId, :productId, 'CB-1KG', 'Coffee', 1, 2450, 'BRL', :at, 0)",
            ).bind("id", UUID.randomUUID())
            .bind("cartId", id)
            .bind("productId", UUID.randomUUID())
            .bind("at", updatedAt)
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
        return id
    }

    private fun exists(id: UUID): Boolean =
        database
            .sql("SELECT count(*) AS n FROM cart WHERE id = :id")
            .bind("id", id)
            .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)) }
            .one()
            .block(QUERY_TIMEOUT) == 1L

    @Test
    fun `anonymous carts idle for more than 30 days are deleted with their lines`() {
        val idle = insertCart(null, "idle-" + UUID.randomUUID(), IDLE_DAYS)
        val recent = insertCart(null, "recent-" + UUID.randomUUID(), RECENT_DAYS)
        val account = insertCart(UUID.randomUUID(), null, IDLE_DAYS * 2)

        val purged = runBlocking { purgeJob.purgeNow() }

        purged shouldBeGreaterThanOrEqual 1
        exists(idle) shouldBe false
        exists(recent) shouldBe true
        exists(account) shouldBe true
        database
            .sql("SELECT count(*) AS n FROM cart_line WHERE cart_id = :id")
            .bind("id", idle)
            .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)) }
            .one()
            .block(QUERY_TIMEOUT) shouldBe 0L
        purgeJob.isRunning shouldBe true
    }
}

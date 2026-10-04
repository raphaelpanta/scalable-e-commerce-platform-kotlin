package com.ecommerce.payment.infrastructure

import com.ecommerce.payment.infrastructure.jobs.CancelledOrderPurgeJob
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

private val PURGE_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * The bounded retention of the cancelled orders payment remembers (data-model section 5, FR-007): the purge job
 * forgets the rows, contact snapshot included, recorded longer ago than the late-charge window (33 minutes by
 * default: the 30-minute payment window plus three retries 60 seconds apart) and keeps the recent ones, which a late
 * approval still needs.
 */
class CancelledOrderPurgeIT : PaymentIntegrationTest() {
    @Autowired
    lateinit var purgeJob: CancelledOrderPurgeJob

    @Autowired
    lateinit var properties: PaymentProperties

    private fun remember(
        orderId: UUID,
        age: String,
    ) {
        column(
            "INSERT INTO cancelled_orders (order_id, account_id, email, phone, preferred_channels, recorded_at) " +
                "VALUES (:id, :owner, 'ada@example.test', '+5511912345678', ARRAY['email','sms'], " +
                "now() - CAST(:age AS interval)) RETURNING order_id",
            "id" to orderId,
            "owner" to UUID.randomUUID(),
            "age" to age,
        )
    }

    private fun remembered(orderId: UUID): List<Any?> =
        column("SELECT email FROM cancelled_orders WHERE order_id = :id", "id" to orderId)

    @Test
    fun `the default retention is the late-charge window`() {
        properties.retentionOfCancelledOrders() shouldBe Duration.ofMinutes(33)
    }

    @Test
    fun `cancelled orders older than the late-charge window are purged and recent ones are kept`() {
        val old = UUID.randomUUID()
        val justPast = UUID.randomUUID()
        val recent = UUID.randomUUID()
        val withinWindow = UUID.randomUUID()
        remember(old, "2 days")
        remember(justPast, "34 minutes")
        remember(recent, "0 seconds")
        remember(withinWindow, "32 minutes")

        val purged = checkNotNull(mono { purgeJob.purgeNow() }.block(PURGE_TIMEOUT))

        purged shouldBeGreaterThanOrEqual 2L
        remembered(old) shouldBe emptyList()
        remembered(justPast) shouldBe emptyList()
        remembered(recent) shouldBe listOf("ada@example.test")
        remembered(withinWindow) shouldBe listOf("ada@example.test")
    }
}

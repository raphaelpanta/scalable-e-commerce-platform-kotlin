package com.ecommerce.notification.infrastructure

import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.notification.infrastructure.PactMessages.ADA
import com.ecommerce.notification.infrastructure.PactMessages.CONSUMER
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_PAID
import com.ecommerce.notification.infrastructure.PactMessages.PAYMENT_APPROVED
import com.ecommerce.notification.infrastructure.PactMessages.REFUND
import com.ecommerce.notification.infrastructure.PactMessages.REFUND_RECORDED
import com.ecommerce.notification.infrastructure.PactMessages.bodyOf
import com.ecommerce.notification.infrastructure.PactMessages.envelope
import com.ecommerce.notification.infrastructure.PactMessages.eventId
import com.ecommerce.notification.infrastructure.PactMessages.message
import com.ecommerce.notification.infrastructure.PactMessages.money
import com.ecommerce.notification.infrastructure.PactMessages.recipient
import com.ecommerce.platform.messaging.envelope.Topic
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import

private const val PROVIDER = "payment"
private const val REFUNDED = 19800L

/**
 * Consumer `notification`, provider `payment` (pact-interactions.md §3.2): `RefundRecorded`, delivered twice through
 * the real Kafka listener; the duplicate produces nothing (FR-022).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V4, providerType = ProviderType.ASYNCH)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
class PaymentConsumerPactTest {
    @Autowired
    lateinit var harness: ConsumerHarness

    @BeforeEach
    fun reset() = harness.reset()

    @Pact(consumer = CONSUMER)
    fun refundRecorded(builder: PactBuilder): V4Pact =
        message(
            builder,
            "a RefundRecorded event for the refund confirmation",
            "a refund was recorded",
            mapOf("refundId" to REFUND, "paymentId" to PAYMENT_APPROVED, "orderId" to ORDER_PAID, "accountId" to ADA),
            Topic.PAYMENT,
            PAYMENT_APPROVED,
            envelope(REFUND_RECORDED, "RefundRecorded", "2026-10-02T11:00:00Z", PAYMENT_APPROVED, PROVIDER) { payload ->
                payload.stringValue("orderId", ORDER_PAID)
                payload.stringValue("accountId", ADA)
                money(payload, "amount", REFUNDED)
                recipient(payload)
            },
        )

    @Test
    @PactTestFor(pactMethod = "refundRecorded")
    fun `a RefundRecorded event twice queues one refund confirmation`(message: V4Interaction.AsynchronousMessage) {
        repeat(2) { harness.deliver(Topic.PAYMENT, PAYMENT_APPROVED, bodyOf(message)) }
        harness.notificationsOf(eventId(REFUND_RECORDED)) shouldBe listOf("refund_confirmation/email/queued")
        harness.bodiesOf(eventId(REFUND_RECORDED)).single() shouldContain "BRL 198.00"
        harness.processed(eventId(REFUND_RECORDED)) shouldBe 1L
    }
}

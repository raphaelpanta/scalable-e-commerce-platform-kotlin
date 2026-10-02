package com.ecommerce.payment.infrastructure

import au.com.dius.pact.consumer.dsl.PactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.payment.infrastructure.PactFixtures.ADA
import com.ecommerce.payment.infrastructure.PactFixtures.APPROVE_TOKEN
import com.ecommerce.payment.infrastructure.PactFixtures.ATTEMPT_APPROVED
import com.ecommerce.payment.infrastructure.PactFixtures.CORRELATION_ID
import com.ecommerce.payment.infrastructure.PactFixtures.KEY_1
import com.ecommerce.payment.infrastructure.PactFixtures.ORDER_1
import com.ecommerce.payment.infrastructure.PactFixtures.ORDER_CANCELLED_EVENT
import com.ecommerce.payment.infrastructure.PactFixtures.ORDER_PLACED_EVENT
import com.ecommerce.payment.infrastructure.PactFixtures.TOTAL_1
import com.ecommerce.payment.infrastructure.PactFixtures.bodyOf
import com.ecommerce.payment.infrastructure.PactFixtures.envelope
import com.ecommerce.payment.infrastructure.PactFixtures.eventId
import com.ecommerce.payment.infrastructure.PactFixtures.message
import com.ecommerce.payment.infrastructure.PactFixtures.money
import com.ecommerce.platform.messaging.envelope.Topic
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import

private const val CONSUMER = "payment"
private const val PROVIDER = "order"

/**
 * Consumer `payment`, provider `order` (pact-interactions.md section 3.2): `OrderPlaced` triggers the charge and
 * `OrderCancelled` the refund. Each message is delivered twice (same `eventId`) through the real Kafka listener,
 * handlers and use cases: the duplicate changes nothing (FR-022).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V4, providerType = ProviderType.ASYNCH)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
class OrderConsumerPactTest {
    @Autowired
    lateinit var harness: PaymentHarness

    @BeforeEach
    fun reset() = harness.reset()

    @Pact(consumer = CONSUMER)
    fun orderPlaced(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderPlaced event that triggers the charge",
            "an order was placed",
            mapOf("orderId" to ORDER_1, "accountId" to ADA),
            Topic.ORDER,
            ORDER_1,
            envelope(ORDER_PLACED_EVENT, "OrderPlaced", "2026-10-02T10:15:00Z", ORDER_1, PROVIDER) { payload ->
                payload.stringValue("orderId", ORDER_1)
                payload.stringValue("accountId", ADA)
                money(payload, "total", TOTAL_1)
                payload.stringValue("paymentMethodRef", APPROVE_TOKEN)
                payload.stringValue("idempotencyKey", KEY_1)
            },
        )

    @Pact(consumer = CONSUMER)
    fun orderCancelled(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderCancelled event that triggers the refund",
            "an order was cancelled",
            mapOf(
                "orderId" to ORDER_1,
                "accountId" to ADA,
                "reason" to "SHOPPER_REQUEST",
                "paymentStatus" to "approved",
            ),
            Topic.ORDER,
            ORDER_1,
            envelope(ORDER_CANCELLED_EVENT, "OrderCancelled", "2026-10-02T10:45:00Z", ORDER_1, PROVIDER) { payload ->
                payload.stringValue("orderId", ORDER_1)
                payload.stringValue("accountId", ADA)
                payload.stringValue("paymentStatus", "approved")
                payload.booleanValue("refundRequired", true)
                payload.stringValue("paymentId", ATTEMPT_APPROVED)
                payload.`object`("recipient") { recipient ->
                    recipient.stringValue("accountId", ADA)
                    recipient.stringValue("email", "ada@example.test")
                    recipient.nullValue("phone")
                    recipient.array("preferredChannels") { it.stringValue("email") }
                }
            },
        )

    @Test
    @PactTestFor(pactMethod = "orderPlaced")
    fun `an OrderPlaced event twice charges the order once under the checkout key`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        repeat(2) { harness.deliverOrderEvent(ORDER_1, bodyOf(message)) }

        harness.attemptsOf(ORDER_1) shouldContainExactly listOf("approved/$KEY_1")
        harness.processed(eventId(ORDER_PLACED_EVENT)) shouldContainExactly listOf(CONSUMER)
        harness.outbox() shouldContainExactly listOf("PaymentApproved/$CORRELATION_ID")
    }

    @Test
    @PactTestFor(pactMethod = "orderPlaced")
    fun `an OrderPlaced event converges with the synchronous charge of the same key`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        harness.store(PactFixtures.approvedCharge)

        harness.deliverOrderEvent(ORDER_1, bodyOf(message))

        harness.attemptsOf(ORDER_1) shouldContainExactly listOf("approved/$KEY_1")
        harness.outbox().shouldBeEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "orderCancelled")
    fun `an OrderCancelled event twice records one refund and publishes one RefundRecorded`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        harness.store(PactFixtures.approvedCharge)

        repeat(2) { harness.deliverOrderEvent(ORDER_1, bodyOf(message)) }

        harness.refundsOf(ORDER_1) shouldContainExactly listOf("$ATTEMPT_APPROVED/$TOTAL_1/true")
        harness.outbox() shouldContainExactly listOf("RefundRecorded/$CORRELATION_ID")
        harness.processed(eventId(ORDER_CANCELLED_EVENT)) shouldContainExactly listOf(CONSUMER)
    }
}

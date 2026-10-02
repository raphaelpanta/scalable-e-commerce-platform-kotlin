package com.ecommerce.notification.infrastructure

import au.com.dius.pact.consumer.dsl.LambdaDslObject
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
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_CANCELLED
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_DECLINED
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_DELIVERED
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_PAID
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_PAID_EVENT
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_PAYMENT_FAILED
import com.ecommerce.notification.infrastructure.PactMessages.ORDER_SHIPPED
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

private const val PROVIDER = "order"
private const val ORDER_NUMBER = "ORD-20261002-0001"
private const val PAID_TOTAL = 19800L
private const val DECLINED_TOTAL = 4913L
private const val ESPRESSO_PRICE = 14900L
private const val BEANS_PRICE = 2450L

/**
 * Consumer `notification`, provider `order` (pact-interactions.md §3.2): every order event the service reads,
 * delivered twice through the real Kafka listener; the duplicate produces nothing (FR-022).
 */
@Suppress("TooManyFunctions") // one pact method and one test per interaction of pact-interactions.md
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V4, providerType = ProviderType.ASYNCH)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
class OrderConsumerPactTest {
    @Autowired
    lateinit var harness: ConsumerHarness

    @BeforeEach
    fun reset() = harness.reset()

    @Pact(consumer = CONSUMER)
    fun orderPaid(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderPaid event for the order confirmation",
            "an order was paid",
            mapOf("orderId" to ORDER_PAID, "accountId" to ADA),
            Topic.ORDER,
            ORDER_PAID,
            envelope(ORDER_PAID_EVENT, "OrderPaid", "2026-10-02T10:15:01Z", ORDER_PAID, PROVIDER) { payload ->
                order(payload, ORDER_PAID)
                payload.array("lines") { lines ->
                    lines.`object` { line(it, "Espresso Machine", 1, ESPRESSO_PRICE) }
                    lines.`object` { line(it, "Coffee Beans 1kg", 2, BEANS_PRICE) }
                }
                money(payload, "total", PAID_TOTAL)
                payload.`object`("deliveryAddress") { address ->
                    address.stringValue("recipientName", "Ada Lovelace")
                    address.stringValue("line1", "12 Analytical Street")
                    address.stringValue("line2", "Flat 2")
                    address.stringValue("city", "London")
                    address.stringValue("postalCode", "N1 9GU")
                    address.stringValue("country", "GB")
                }
                recipient(payload)
            },
        )

    @Pact(consumer = CONSUMER)
    fun orderPaymentFailed(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderPaymentFailed event for the payment failure message",
            "an order payment failed",
            mapOf("orderId" to ORDER_DECLINED, "accountId" to ADA, "reasonCategory" to "insufficient_funds"),
            Topic.ORDER,
            ORDER_DECLINED,
            envelope(
                ORDER_PAYMENT_FAILED,
                "OrderPaymentFailed",
                "2026-10-02T10:30:00Z",
                ORDER_DECLINED,
                PROVIDER,
            ) { payload ->
                order(payload, ORDER_DECLINED, "ORD-20261002-0003")
                money(payload, "total", DECLINED_TOTAL)
                payload.stringValue("reasonCategory", "insufficient_funds")
                recipient(payload)
            },
        )

    @Pact(consumer = CONSUMER)
    fun orderShipped(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderShipped event for the shipping message",
            "an order was shipped",
            mapOf("orderId" to ORDER_PAID, "accountId" to ADA),
            Topic.ORDER,
            ORDER_PAID,
            envelope(ORDER_SHIPPED, "OrderShipped", "2026-10-03T09:00:00Z", ORDER_PAID, PROVIDER) { payload ->
                order(payload, ORDER_PAID)
                recipient(payload)
            },
        )

    @Pact(consumer = CONSUMER)
    fun orderDelivered(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderDelivered event for the delivery message",
            "an order was delivered",
            mapOf("orderId" to ORDER_PAID, "accountId" to ADA),
            Topic.ORDER,
            ORDER_PAID,
            envelope(ORDER_DELIVERED, "OrderDelivered", "2026-10-05T14:30:00Z", ORDER_PAID, PROVIDER) { payload ->
                order(payload, ORDER_PAID)
                recipient(payload)
            },
        )

    @Pact(consumer = CONSUMER)
    fun orderCancelled(builder: PactBuilder): V4Pact =
        message(
            builder,
            "an OrderCancelled event for the cancellation message",
            "an order was cancelled",
            mapOf(
                "orderId" to ORDER_PAID,
                "accountId" to ADA,
                "reason" to "SHOPPER_REQUEST",
                "paymentStatus" to "approved",
            ),
            Topic.ORDER,
            ORDER_PAID,
            envelope(ORDER_CANCELLED, "OrderCancelled", "2026-10-02T10:45:00Z", ORDER_PAID, PROVIDER) { payload ->
                order(payload, ORDER_PAID)
                money(payload, "total", PAID_TOTAL)
                payload.stringValue("reason", "SHOPPER_REQUEST")
                payload.booleanValue("refundRequired", true)
                recipient(payload)
            },
        )

    @Test
    @PactTestFor(pactMethod = "orderPaid")
    fun `an OrderPaid event twice queues one order confirmation email`(message: V4Interaction.AsynchronousMessage) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ORDER_PAID_EVENT)) shouldBe listOf("order_confirmation/email/queued")
        val body = harness.bodiesOf(eventId(ORDER_PAID_EVENT)).single()
        listOf(
            ORDER_NUMBER,
            ORDER_PAID,
            "2 x Coffee Beans 1kg at BRL 24.50",
            "Total: BRL 198.00",
            "12 Analytical Street",
        ).forEach { body shouldContain it }
        harness.processed(eventId(ORDER_PAID_EVENT)) shouldBe 1L
    }

    @Test
    @PactTestFor(pactMethod = "orderPaymentFailed")
    fun `an OrderPaymentFailed event twice queues one payment failure message`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ORDER_PAYMENT_FAILED)) shouldBe listOf("payment_failure/email/queued")
        harness.bodiesOf(eventId(ORDER_PAYMENT_FAILED)).single() shouldContain "insufficient funds"
    }

    @Test
    @PactTestFor(pactMethod = "orderShipped")
    fun `an OrderShipped event twice queues one message per permitted channel`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ORDER_SHIPPED)) shouldBe listOf("order_shipped/email/queued")
    }

    @Test
    @PactTestFor(pactMethod = "orderDelivered")
    fun `an OrderDelivered event twice queues one message per permitted channel`(
        message: V4Interaction.AsynchronousMessage,
    ) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ORDER_DELIVERED)) shouldBe listOf("order_delivered/email/queued")
    }

    @Test
    @PactTestFor(pactMethod = "orderCancelled")
    fun `an OrderCancelled event twice queues one cancellation message`(message: V4Interaction.AsynchronousMessage) {
        deliverTwice(message)
        harness.notificationsOf(eventId(ORDER_CANCELLED)) shouldBe listOf("order_cancelled/email/queued")
        harness.bodiesOf(eventId(ORDER_CANCELLED)).single() shouldContain "A refund of BRL 198.00 follows."
    }

    private fun deliverTwice(message: V4Interaction.AsynchronousMessage) {
        val body = bodyOf(message)
        val key = if (body.contains(ORDER_DECLINED)) ORDER_DECLINED else ORDER_PAID
        repeat(2) { harness.deliver(Topic.ORDER, key, body) }
    }

    private companion object {
        fun order(
            payload: LambdaDslObject,
            orderId: String,
            orderNumber: String = ORDER_NUMBER,
        ) {
            payload.stringValue("orderId", orderId)
            payload.stringType("orderNumber", orderNumber)
            payload.stringValue("accountId", ADA)
        }

        fun line(
            line: LambdaDslObject,
            name: String,
            quantity: Int,
            unitPriceMinor: Long,
        ) {
            line.stringValue("name", name)
            line.numberValue("quantity", quantity)
            money(line, "unitPrice", unitPriceMinor)
        }
    }
}

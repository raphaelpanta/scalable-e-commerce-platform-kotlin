package com.ecommerce.order.infrastructure

import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
import com.ecommerce.order.domain.OrderChange
import com.ecommerce.order.domain.OrderEvent
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderLine
import com.ecommerce.order.domain.OrderNumber
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.Placement
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.Quantity
import com.ecommerce.order.domain.Recipient
import com.ecommerce.order.domain.ReservationId
import com.ecommerce.order.domain.applyPayment
import com.ecommerce.order.domain.cancelByShopper
import com.ecommerce.order.domain.expirePayment
import com.ecommerce.order.domain.transition
import com.ecommerce.order.infrastructure.messaging.OutboxOrderEventPublisher
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventEnvelope
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The order events of contracts/internal/pact-interactions.md section 3.1, produced from real domain objects by the
 * same mapping the outbox uses: the fixture orders 1 (paid), 2 (pending, then expired) and 3 (declined).
 */
@Suppress("TooManyFunctions", "MagicNumber") // one builder per example; the values are the fixtures of the examples
object OrderEventExamples {
    const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
    val ADA = AccountId(UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"))
    val OPERATOR = AccountId(UUID.fromString("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"))
    val PAID_ORDER = OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"))
    val PENDING_ORDER = OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11"))
    val DECLINED_ORDER = OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"))
    private val DAY = LocalDate.of(2026, 10, 2)
    private val ESPRESSO = ProductId(UUID.fromString("9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01"))
    private val BEANS = ProductId(UUID.fromString("3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02"))
    private val ADDRESS =
        DeliveryAddress("Ada Lovelace", "12 Analytical Street", "Flat 2", "London", "England", "N1 9GU", "GB")
    private val RECIPIENT = Recipient("ada@example.test", null, listOf(Channel.EMAIL))

    fun orderPlaced(): String = json(placed(PAID_ORDER, 1, paidLines(), "tok_sim_approve_4242", KEY_1, "10:15:00"))

    fun orderPaid(): String = json(paid())

    fun orderPaymentFailed(): String =
        json(
            placed(
                DECLINED_ORDER,
                3,
                listOf(line(BEANS, "CB-1KG", "Coffee Beans 1kg", 4913, 1)),
                "tok",
                KEY_3,
                "10:29:59",
            ).order
                .applyPayment(
                    PaymentOutcome.Declined(
                        PaymentAttemptId(UUID.fromString("8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e")),
                        DeclineCategory.INSUFFICIENT_FUNDS,
                    ),
                    at("10:30:00"),
                ),
        )

    fun orderShipped(): String = json(shipped())

    fun orderDelivered(): String =
        json(
            checkNotNull(
                shipped()
                    .order
                    .transition(
                        OrderStatus.DELIVERED,
                        OPERATOR,
                        Instant.parse("2026-10-05T14:30:00Z"),
                    ).getOrNull(),
            ),
        )

    fun orderCancelledByShopper(): String = json(checkNotNull(paid().order.cancelByShopper(at("10:45:00")).getOrNull()))

    fun orderCancelledAfterExpiry(): String =
        json(
            placed(
                PENDING_ORDER,
                2,
                listOf(line(BEANS, "CB-1KG", "Coffee Beans 1kg", 2450, 2)),
                "tok_sim_unreachable",
                KEY_2,
                "10:20:00",
            ).order.expirePayment(at("10:50:00")),
        )

    /** Message metadata of an order event. */
    fun metadata(orderId: OrderId): Map<String, Any> = mapOf("topic" to Topic.ORDER, "kafkaKey" to orderId.toString())

    private val KEY_1 = IdempotencyKey(UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"))
    private val KEY_2 = IdempotencyKey(UUID.fromString("3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92"))
    private val KEY_3 = IdempotencyKey(UUID.fromString("2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81"))

    private fun paidLines() =
        listOf(
            line(ESPRESSO, "ESP-MACH-01", "Espresso Machine", 14900, 1),
            line(BEANS, "CB-1KG", "Coffee Beans 1kg", 2450, 2),
        )

    private fun paid(): OrderChange =
        placed(PAID_ORDER, 1, paidLines(), "tok_sim_approve_4242", KEY_1, "10:15:00")
            .order
            .applyPayment(
                PaymentOutcome.Approved(PaymentAttemptId(UUID.fromString("c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"))),
                at("10:15:01"),
            )

    private fun shipped(): OrderChange {
        val preparing =
            checkNotNull(paid().order.transition(OrderStatus.PREPARING, OPERATOR, at("12:00:00")).getOrNull())
        return checkNotNull(
            preparing.order
                .transition(
                    OrderStatus.SHIPPED,
                    OPERATOR,
                    Instant.parse("2026-10-03T09:00:00Z"),
                ).getOrNull(),
        )
    }

    @Suppress("LongParameterList") // the fixture order of the examples, field by field
    private fun placed(
        id: OrderId,
        sequence: Long,
        lines: List<OrderLine>,
        token: String,
        key: IdempotencyKey,
        time: String,
    ): OrderChange =
        checkNotNull(
            Order
                .place(
                    Placement(
                        id,
                        OrderNumber.of(DAY, sequence),
                        ADA,
                        lines,
                        ADDRESS,
                        RECIPIENT,
                        token,
                        key,
                        ReservationId(UUID.fromString("4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15")),
                        at(time),
                    ),
                    Order.DEFAULT_PAYMENT_WINDOW,
                ).getOrNull(),
        )

    private fun line(
        id: ProductId,
        sku: String,
        name: String,
        price: Long,
        quantity: Int,
    ) = OrderLine(id, sku, name, Money(price, "BRL"), Quantity(quantity))

    private fun at(time: String): Instant = Instant.parse("2026-10-02T${time}Z")

    /** The envelope of the single event of [change], as the outbox relays it. */
    private fun json(change: OrderChange): String {
        val event: OrderEvent = change.events.single()
        val envelopes = EnvelopeFactory("order", Clock.fixed(event.at, ZoneOffset.UTC))
        val publisher = OutboxOrderEventPublisher(NO_OUTBOX, envelopes)
        return EnvelopeJson.write(publisher.envelopeOf(event, CORRELATION_ID))
    }

    private val NO_OUTBOX =
        object : OutboxPublisher {
            override suspend fun publish(envelope: EventEnvelope): Unit = error("the examples are not stored")
        }
}

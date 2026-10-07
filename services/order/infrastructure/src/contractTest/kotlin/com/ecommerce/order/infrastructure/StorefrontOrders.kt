package com.ecommerce.order.infrastructure

import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.Order
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
import com.ecommerce.order.domain.transition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The account a storefront provider state signs in: the subject and role of the bearer the verifier sends. */
data class StorefrontCaller(
    val accountId: UUID,
    val role: String,
) {
    companion object {
        /** ana@example.com, the shopper of every storefront interaction. */
        val ANA = StorefrontCaller(UUID.fromString(PactValues.ADA), "shopper")

        /** ops@example.com, the operator of the console interactions. */
        val OPERATOR = StorefrontCaller(OrderEventExamples.OPERATOR.value, "operator")
    }
}

/**
 * The orders the storefront provider states seed (pact-matrix.md "Storefront to order"), as domain objects built by
 * the domain's own transitions: the two-line order of [StorefrontCart] placed by ana (or another shopper) a moment
 * ago, so that a pending payment window is still open, and paid, shipped or delivered from there.
 */
object StorefrontOrders {
    val ANA = AccountId(UUID.fromString(PactValues.ADA))
    val OTHER_SHOPPER = AccountId(UUID.fromString(PactValues.OTHER_ACCOUNT))
    val ORDER_1 = OrderId(UUID.fromString(PactValues.ORDER_1))
    val ORDER_2 = OrderId(UUID.fromString(PactValues.ORDER_2))
    val ORDER_3 = OrderId(UUID.fromString(PactValues.ORDER_3))
    val ORDER_4 = OrderId(UUID.fromString(PactValues.ORDER_4))
    val ORDER_5 = OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a14"))

    private val OPERATOR = OrderEventExamples.OPERATOR
    private val ADDRESS =
        DeliveryAddress("Ada Lovelace", "12 Analytical Street", null, "London", "England", "N1 9GU", "GB")
    private val RECIPIENT = Recipient("ana@example.com", null, listOf(Channel.EMAIL))
    private val LINES =
        listOf(
            line(
                PactValues.ESPRESSO,
                StorefrontCart.ESPRESSO_SKU,
                StorefrontCart.ESPRESSO_NAME,
                StorefrontCart.ESPRESSO_PRICE,
                StorefrontCart.ESPRESSO_QUANTITY,
            ),
            line(
                PactValues.BEANS,
                StorefrontCart.BEANS_SKU,
                StorefrontCart.BEANS_NAME,
                StorefrontCart.BEANS_PRICE,
                StorefrontCart.BEANS_QUANTITY,
            ),
        )

    /** `placed` / `pending`: placed [sequence] minutes ago (the sequence also numbers the order), charge unsettled. */
    fun pending(
        id: OrderId = ORDER_1,
        owner: AccountId = ANA,
        sequence: Long = 1,
    ): Order {
        val placedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofMinutes(sequence))
        val placement =
            Placement(
                id = id,
                number = OrderNumber.of(LocalDate.ofInstant(placedAt, ZoneOffset.UTC), sequence),
                accountId = owner,
                lines = LINES,
                deliveryAddress = ADDRESS,
                recipient = RECIPIENT,
                paymentMethodRef = StorefrontCart.APPROVE_TOKEN,
                idempotencyKey = IdempotencyKey(UUID.randomUUID()),
                reservationId = ReservationId(UUID.fromString(PactValues.RESERVATION)),
                placedAt = placedAt,
            )
        return checkNotNull(Order.place(placement, Order.DEFAULT_PAYMENT_WINDOW).getOrNull()).order
    }

    /** `placed` / `approved` with the approved attempt of the pact fixtures. */
    fun paid(
        id: OrderId = ORDER_1,
        owner: AccountId = ANA,
        sequence: Long = 1,
    ): Order {
        val placed = pending(id, owner, sequence)
        val attempt = PaymentAttemptId(UUID.fromString(PactValues.ATTEMPT_APPROVED))
        return placed.applyPayment(PaymentOutcome.Approved(attempt), placed.placedAt.plusSeconds(1)).order
    }

    /** `shipped` / `approved`: moved through `preparing` by the operator. */
    fun shipped(
        id: OrderId = ORDER_1,
        owner: AccountId = ANA,
    ): Order = paid(id, owner).moved(OrderStatus.PREPARING).moved(OrderStatus.SHIPPED)

    /** `delivered` / `approved`. */
    fun delivered(
        id: OrderId = ORDER_1,
        owner: AccountId = ANA,
    ): Order = shipped(id, owner).moved(OrderStatus.DELIVERED)

    private fun Order.moved(target: OrderStatus): Order =
        checkNotNull(transition(target, OPERATOR, Instant.now().truncatedTo(ChronoUnit.MICROS)).getOrNull()).order

    private fun line(
        productId: String,
        sku: String,
        name: String,
        price: Long,
        quantity: Int,
    ): OrderLine = OrderLine(ProductId(UUID.fromString(productId)), sku, name, Money(price, "BRL"), Quantity(quantity))
}

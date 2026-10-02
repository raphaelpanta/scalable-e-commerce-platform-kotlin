package com.ecommerce.order.domain

import java.time.Instant

private const val MASK = "***"

/**
 * Delivery address frozen at confirmation (personal data: `toString` is masked, never log the fields). [country] is
 * the ISO 3166-1 alpha-2 code the identity context calls `countryCode`.
 */
data class DeliveryAddress(
    val recipientName: String,
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String?,
    val postalCode: String,
    val country: String,
) {
    override fun toString(): String = "DeliveryAddress($MASK, country=$country)"

    /** The snapshot with every personal field replaced; only the country survives the scrub (FR-007). */
    fun scrubbed(): DeliveryAddress = DeliveryAddress(REDACTED, REDACTED, null, REDACTED, null, REDACTED, country)

    companion object {
        /** Placeholder of a scrubbed field. */
        const val REDACTED: String = "[redacted]"
    }
}

/**
 * Point-in-time contact data carried by the order events so that notification never calls back (events.yaml
 * `RecipientSnapshot`). Personal data: `toString` is masked.
 */
data class Recipient(
    val email: String,
    val phone: String?,
    val channels: List<Channel>,
) {
    init {
        require(channels.isNotEmpty()) { "a recipient needs at least one channel" }
    }

    override fun toString(): String = "Recipient($MASK, channels=$channels)"

    /** The snapshot of an anonymised account: an undeliverable placeholder address, no phone, email only. */
    fun anonymised(pseudonym: String): Recipient =
        Recipient("$pseudonym@$ANONYMISED_DOMAIN", null, listOf(Channel.EMAIL))

    companion object {
        /** Reserved, undeliverable domain of anonymised accounts (identity-internal.yaml). */
        const val ANONYMISED_DOMAIN: String = "anonymised.invalid"

        /** The snapshot of a contact; without a permitted channel the recipient still has email. */
        fun of(
            email: String,
            phone: String?,
            channels: List<Channel>,
        ): Recipient = Recipient(email, phone, channels.distinct().ifEmpty { listOf(Channel.EMAIL) })
    }
}

/** One frozen order line; [lineTotal] = [unitPrice] x [quantity]. */
data class OrderLine(
    val productId: ProductId,
    val sku: String,
    val name: String,
    val unitPrice: Money,
    val quantity: Quantity,
) {
    val lineTotal: Money get() = unitPrice * quantity
}

/** Which of the two statuses a [StatusChange] concerns. */
enum class StatusKind(
    val wire: String,
) {
    ORDER("order"),
    PAYMENT("payment"),
}

/** One append-only entry of the status history: [status] is the new wire value of the [kind] status. */
data class StatusChange(
    val kind: StatusKind,
    val from: String?,
    val status: String,
    val at: Instant,
    val by: String,
    val reason: CancellationReason? = null,
) {
    companion object {
        /** A change of the order status. */
        fun order(
            from: OrderStatus?,
            to: OrderStatus,
            at: Instant,
            actor: Actor,
            reason: CancellationReason? = null,
        ): StatusChange = StatusChange(StatusKind.ORDER, from?.wire, to.wire, at, actor.by, reason)

        /** A change of the payment status, always caused by the platform. */
        fun payment(
            from: PaymentStatus?,
            to: PaymentStatus,
            at: Instant,
        ): StatusChange = StatusChange(StatusKind.PAYMENT, from?.wire, to.wire, at, Actor.System.by)
    }
}

/** The cancellation of an order: present if and only if the order status is `cancelled`. */
data class Cancellation(
    val reason: CancellationReason,
    val at: Instant,
    val by: String,
)

/** A refund recorded by the payment context after the cancellation of a paid order. */
data class Refund(
    val refundId: RefundId,
    val recordedAt: Instant,
)

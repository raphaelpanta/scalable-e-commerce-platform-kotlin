package com.ecommerce.notification.domain

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val MASK = "***"
private const val MINOR_UNITS = 100

/** The rendered message: [subject] and plain-text [body]. `toString()` never shows the body (it may hold a link). */
data class MessageContent(
    val subject: String,
    val body: String,
) {
    override fun toString(): String = "MessageContent(subject=$subject, body=${body.length} characters)"
}

/** An amount in minor units of [currency] (events.yaml `Money`), shown as `BRL 198.00`. */
data class Amount(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(amountMinor >= 0) { "amounts are never negative" }
    }

    fun formatted(): String {
        val cents = (amountMinor % MINOR_UNITS).toString().padStart(2, '0')
        return "$currency ${amountMinor / MINOR_UNITS}.$cents"
    }
}

/** One order line as frozen at confirmation. */
data class LineSummary(
    val name: String,
    val quantity: Int,
    val unitPrice: Amount,
)

/** The delivery address snapshot of an order. Personal data: `toString()` is masked. */
data class AddressSummary(
    val recipientName: String,
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val country: String,
) {
    /** The address as message lines. */
    fun lines(): List<String> = listOfNotNull(recipientName, line1, line2, "$postalCode $city", country)

    override fun toString(): String = "AddressSummary($MASK)"
}

/**
 * A link carrying a single-use secret token (verification, password reset): `<baseUrl><path>?token=<token>`, the
 * token URL-encoded. `toString()` masks the token, so a link never reaches a log line.
 */
class SecretLink private constructor(
    private val prefix: String,
    private val token: String,
) {
    /** The full link, for the message body only. */
    val value: String get() = prefix + URLEncoder.encode(token, StandardCharsets.UTF_8)

    override fun toString(): String = prefix + MASK

    override fun equals(other: Any?): Boolean = other is SecretLink && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** The link to [path] under [baseUrl] (trailing slashes ignored) with [token]. */
        fun of(
            baseUrl: String,
            path: String,
            token: String,
        ): SecretLink = SecretLink(baseUrl.trimEnd('/') + path + "?token=", token)
    }
}

/** What a message says, per kind (data-model §3.6 "Content per kind"). */
sealed interface TemplateData {
    val kind: NotificationKind

    /** `AccountRegistered`: verify the email address. */
    data class AccountVerification(
        val link: SecretLink,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.ACCOUNT_VERIFICATION
    }

    /** `PasswordResetRequested`: choose a new password. */
    data class PasswordReset(
        val link: SecretLink,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.PASSWORD_RESET
    }

    /** `OrderPaid`: order number and id, lines, total and delivery address (US6.1). */
    data class OrderConfirmation(
        val order: OrderReference,
        val lines: List<LineSummary>,
        val total: Amount,
        val address: AddressSummary,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.ORDER_CONFIRMATION
    }

    /** `OrderPaymentFailed`: the decline category (US6.1). */
    data class PaymentFailure(
        val order: OrderReference,
        val total: Amount,
        val declineCategory: String,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.PAYMENT_FAILURE
    }

    /** `OrderShipped`. */
    data class OrderShipped(
        val order: OrderReference,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.ORDER_SHIPPED
    }

    /** `OrderDelivered`. */
    data class OrderDelivered(
        val order: OrderReference,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.ORDER_DELIVERED
    }

    /** `OrderCancelled`: the reason and whether a refund follows. */
    data class OrderCancelled(
        val order: OrderReference,
        val total: Amount,
        val reason: String,
        val refundRequired: Boolean,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.ORDER_CANCELLED
    }

    /** `RefundRecorded`: the refunded amount. */
    data class RefundConfirmation(
        val orderId: OrderId,
        val amount: Amount,
    ) : TemplateData {
        override val kind: NotificationKind get() = NotificationKind.REFUND_CONFIRMATION
    }
}

/** An order as messages name it: the human order number and the order id (conventions §8 "Order number"). */
data class OrderReference(
    val orderId: OrderId,
    val orderNumber: String,
) {
    fun label(): String = "$orderNumber (order id $orderId)"
}

/**
 * Message templates as pure functions: one subject and body per [TemplateData] and channel. SMS bodies are one
 * short line; email bodies carry every detail the kind requires.
 */
object Templates {
    /** The order a message is about, if any. */
    fun orderOf(data: TemplateData): OrderId? =
        when (data) {
            is TemplateData.AccountVerification, is TemplateData.PasswordReset -> null
            is TemplateData.OrderConfirmation -> data.order.orderId
            is TemplateData.PaymentFailure -> data.order.orderId
            is TemplateData.OrderShipped -> data.order.orderId
            is TemplateData.OrderDelivered -> data.order.orderId
            is TemplateData.OrderCancelled -> data.order.orderId
            is TemplateData.RefundConfirmation -> data.orderId
        }

    /** The message for [data] on [channel]. */
    fun render(
        data: TemplateData,
        channel: NotificationChannel,
    ): MessageContent {
        val email = EmailTemplates.email(data)
        return if (channel == NotificationChannel.SMS) MessageContent(email.subject, sms(data)) else email
    }

    private fun sms(data: TemplateData): String =
        when (data) {
            is TemplateData.AccountVerification, is TemplateData.PasswordReset -> {
                "Check your email to continue."
            }

            is TemplateData.OrderConfirmation -> {
                "Order ${data.order.orderNumber} confirmed, total ${data.total.formatted()}."
            }

            is TemplateData.PaymentFailure -> {
                "Payment for order ${data.order.orderNumber} failed: ${humanise(data.declineCategory)}."
            }

            is TemplateData.OrderShipped -> {
                "Order ${data.order.orderNumber} has shipped."
            }

            is TemplateData.OrderDelivered -> {
                "Order ${data.order.orderNumber} was delivered."
            }

            is TemplateData.OrderCancelled -> {
                "Order ${data.order.orderNumber} was cancelled."
            }

            is TemplateData.RefundConfirmation -> {
                "Refund of ${data.amount.formatted()} recorded."
            }
        }

    /** `insufficient_funds` reads `insufficient funds`. */
    fun humanise(category: String): String = category.replace('_', ' ')

    /** The shopper-facing text of an order cancellation reason (events.yaml `CancellationReason`). */
    fun cancellationReason(reason: String): String =
        when (reason) {
            "SHOPPER_REQUEST" -> "you asked for the cancellation"
            "OPERATOR" -> "our team cancelled it"
            "PAYMENT_FAILED" -> "the payment failed"
            "PAYMENT_EXPIRED" -> "the payment was not confirmed in time"
            else -> "it can no longer be fulfilled"
        }
}

/** The email bodies of [Templates]: every detail each kind requires (US6.1). */
internal object EmailTemplates {
    fun email(data: TemplateData): MessageContent =
        when (data) {
            is TemplateData.AccountVerification -> verification(data.link)
            is TemplateData.PasswordReset -> passwordReset(data.link)
            is TemplateData.OrderConfirmation -> orderConfirmation(data)
            is TemplateData.PaymentFailure -> paymentFailure(data)
            is TemplateData.OrderShipped -> orderShipped(data.order)
            is TemplateData.OrderDelivered -> orderDelivered(data.order)
            is TemplateData.OrderCancelled -> orderCancelled(data)
            is TemplateData.RefundConfirmation -> refund(data)
        }

    private fun verification(link: SecretLink): MessageContent =
        MessageContent(
            "Verify your email address",
            paragraphs(
                "Welcome! Confirm your email address to activate your account:",
                link.value,
                "The link can be used once and expires in 24 hours.",
            ),
        )

    private fun passwordReset(link: SecretLink): MessageContent =
        MessageContent(
            "Reset your password",
            paragraphs(
                "A password reset was requested for your account. Choose a new password here:",
                link.value,
                "The link can be used once and expires in 1 hour. Ignore this message if you did not ask.",
            ),
        )

    private fun paymentFailure(data: TemplateData.PaymentFailure): MessageContent =
        MessageContent(
            "Payment failed for order ${data.order.orderNumber}",
            paragraphs(
                "The payment for order ${data.order.label()} of ${data.total.formatted()} failed.",
                "Reason: ${Templates.humanise(data.declineCategory)}.",
                "The order was cancelled; your cart is kept so that you can try again.",
            ),
        )

    private fun orderShipped(order: OrderReference): MessageContent =
        MessageContent(
            "Order ${order.orderNumber} has shipped",
            paragraphs("Good news: order ${order.label()} is on its way."),
        )

    private fun orderDelivered(order: OrderReference): MessageContent =
        MessageContent(
            "Order ${order.orderNumber} was delivered",
            paragraphs("Order ${order.label()} was delivered. Enjoy!"),
        )

    private fun orderCancelled(data: TemplateData.OrderCancelled): MessageContent {
        val total = data.total.formatted()
        return MessageContent(
            "Order ${data.order.orderNumber} was cancelled",
            paragraphs(
                "Order ${data.order.label()} of $total was cancelled: ${Templates.cancellationReason(data.reason)}.",
                if (data.refundRequired) "A refund of $total follows." else "Nothing was charged.",
            ),
        )
    }

    private fun refund(data: TemplateData.RefundConfirmation): MessageContent =
        MessageContent(
            "Refund recorded",
            paragraphs("A refund of ${data.amount.formatted()} was recorded for order id ${data.orderId}."),
        )

    private fun orderConfirmation(data: TemplateData.OrderConfirmation): MessageContent =
        MessageContent(
            "Order ${data.order.orderNumber} confirmed",
            paragraphs(
                "Thank you! Your payment was approved and order ${data.order.label()} is confirmed.",
                data.lines.joinToString("\n") { line ->
                    "- ${line.quantity} x ${line.name} at ${line.unitPrice.formatted()}"
                },
                "Total: ${data.total.formatted()}",
                "Delivery address:\n" + data.address.lines().joinToString("\n"),
            ),
        )

    private fun paragraphs(vararg parts: String): String = parts.joinToString("\n\n")
}

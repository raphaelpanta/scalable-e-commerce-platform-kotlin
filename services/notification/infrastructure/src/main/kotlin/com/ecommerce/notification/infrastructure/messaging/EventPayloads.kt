package com.ecommerce.notification.infrastructure.messaging

import com.ecommerce.notification.domain.AddressSummary
import com.ecommerce.notification.domain.Amount
import com.ecommerce.notification.domain.EmailAddress
import com.ecommerce.notification.domain.LineSummary
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.OrderId
import com.ecommerce.notification.domain.OrderReference
import com.ecommerce.notification.domain.PhoneNumber
import com.ecommerce.notification.domain.RecipientContact
import java.util.UUID

// Payloads of the consumed events (contracts/asyncapi/events.yaml), reduced to the members this service reads;
// unknown members are ignored (additive evolution). They hold personal data and tokens: toString() hides them.

/** `RecipientSnapshot`: the contact copy an event carries, used when identity cannot answer. */
data class RecipientSnapshotPayload(
    val email: String,
    val phone: String? = null,
    val preferredChannels: List<String> = emptyList(),
) {
    /** `phone` is present only when it is verified and opted in to SMS (events.yaml). */
    fun toContact(): RecipientContact {
        val phoneNumber = phone?.let { PhoneNumber.of(it).getOrNull() }
        return RecipientContact(
            email = EmailAddress.of(email).getOrNull(),
            phone = phoneNumber,
            phoneVerified = phoneNumber != null,
            channels = preferredChannels.mapNotNull(NotificationChannel::fromWire).toSet(),
            anonymised = false,
        )
    }

    override fun toString(): String = "RecipientSnapshotPayload(***)"
}

/** `Money`. */
data class MoneyPayload(
    val amountMinor: Long,
    val currency: String,
) {
    fun toAmount(): Amount = Amount(amountMinor, currency)
}

/** `AccountRegisteredPayload`. */
data class AccountRegisteredPayload(
    val accountId: UUID,
    val recipient: RecipientSnapshotPayload? = null,
    val verificationToken: String,
) {
    override fun toString(): String = "AccountRegisteredPayload(accountId=$accountId)"
}

/** `PasswordResetRequestedPayload`. */
data class PasswordResetRequestedPayload(
    val accountId: UUID,
    val recipient: RecipientSnapshotPayload? = null,
    val resetToken: String,
) {
    override fun toString(): String = "PasswordResetRequestedPayload(accountId=$accountId)"
}

/** `AccountVerifiedPayload` and `AccountDeletedPayload`: only the account matters here. */
data class AccountPayload(
    val accountId: UUID,
)

/** `OrderLine`. */
data class OrderLinePayload(
    val name: String,
    val quantity: Int,
    val unitPrice: MoneyPayload,
) {
    fun toLine(): LineSummary = LineSummary(name, quantity, unitPrice.toAmount())
}

/** `DeliveryAddress`. */
data class DeliveryAddressPayload(
    val recipientName: String,
    val line1: String,
    val line2: String? = null,
    val city: String,
    val postalCode: String,
    val country: String,
) {
    fun toAddress(): AddressSummary = AddressSummary(recipientName, line1, line2, city, postalCode, country)

    override fun toString(): String = "DeliveryAddressPayload(***)"
}

/** `OrderPaidPayload`. */
data class OrderPaidPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val lines: List<OrderLinePayload>,
    val total: MoneyPayload,
    val deliveryAddress: DeliveryAddressPayload,
    val recipient: RecipientSnapshotPayload? = null,
) {
    val order: OrderReference get() = OrderReference(OrderId(orderId), orderNumber)
}

/** `OrderPaymentFailedPayload`. */
data class OrderPaymentFailedPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val total: MoneyPayload,
    val reasonCategory: String,
    val recipient: RecipientSnapshotPayload? = null,
) {
    val order: OrderReference get() = OrderReference(OrderId(orderId), orderNumber)
}

/** `OrderStatusChangedPayload` (`OrderShipped`, `OrderDelivered`). */
data class OrderStatusChangedPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val recipient: RecipientSnapshotPayload? = null,
) {
    val order: OrderReference get() = OrderReference(OrderId(orderId), orderNumber)
}

/** `OrderCancelledPayload`. */
data class OrderCancelledPayload(
    val orderId: UUID,
    val orderNumber: String,
    val accountId: UUID,
    val total: MoneyPayload,
    val reason: String,
    val refundRequired: Boolean,
    val recipient: RecipientSnapshotPayload? = null,
) {
    val order: OrderReference get() = OrderReference(OrderId(orderId), orderNumber)
}

/** `RefundRecordedPayload`. */
data class RefundRecordedPayload(
    val orderId: UUID,
    val accountId: UUID,
    val amount: MoneyPayload,
    val recipient: RecipientSnapshotPayload? = null,
)

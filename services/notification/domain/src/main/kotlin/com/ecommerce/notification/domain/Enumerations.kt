package com.ecommerce.notification.domain

/** Outbound channel of a notification (data-model §2 `NotificationChannel`). */
enum class NotificationChannel(
    /** The name used by the contracts (`email`, `sms`). */
    val wire: String,
) {
    EMAIL("email"),
    SMS("sms"),
    ;

    companion object {
        /** The channel named [wire], or null for an unknown name. */
        fun fromWire(wire: String): NotificationChannel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What a notification is about (data-model §2 `NotificationKind`, plus the cancellation and refund messages of
 * events.yaml). [wire] is the stored name, [apiType] the `NotificationType` of notification.yaml (the contract
 * calls the verification message `registration_verification`), [template] the `template` key of
 * `NotificationSent`/`NotificationFailed`. [security] kinds carry a single-use link: they always go by email
 * (data-model §3.6: email cannot be switched off for them) and never by SMS.
 */
enum class NotificationKind(
    val wire: String,
    val apiType: String,
    val template: String,
    val security: Boolean,
) {
    ACCOUNT_VERIFICATION("account_verification", "registration_verification", "account-verification", true),
    PASSWORD_RESET("password_reset", "password_reset", "password-reset", true),
    ORDER_CONFIRMATION("order_confirmation", "order_confirmation", "order-confirmation", false),
    PAYMENT_FAILURE("payment_failure", "payment_failure", "payment-failure", false),
    ORDER_SHIPPED("order_shipped", "order_shipped", "order-shipped", false),
    ORDER_DELIVERED("order_delivered", "order_delivered", "order-delivered", false),
    ORDER_CANCELLED("order_cancelled", "order_cancelled", "order-cancelled", false),
    REFUND_CONFIRMATION("refund_confirmation", "refund_confirmation", "refund-confirmation", false),
    ;

    companion object {
        /** The kind stored as [wire], or null. */
        fun fromWire(wire: String): NotificationKind? = entries.firstOrNull { it.wire == wire }

        /** The kind exposed as the API type [apiType], or null. */
        fun fromApiType(apiType: String): NotificationKind? = entries.firstOrNull { it.apiType == apiType }
    }
}

/**
 * Delivery status (data-model §3.6): `queued` waits for (re)delivery, `sent` is final, `failed` means the retry
 * limit was reached (an operator may re-queue it), `suppressed` means the recipient may not be messaged.
 */
enum class DeliveryStatus(
    val wire: String,
) {
    QUEUED("queued"),
    SENT("sent"),
    FAILED("failed"),
    SUPPRESSED("suppressed"),
    ;

    companion object {
        /** The status named [wire], or null. */
        fun fromWire(wire: String): DeliveryStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** Failure categories of `NotificationFailed.lastErrorCategory` (events.yaml). */
enum class FailureCategory {
    /** The channel could not be reached or refused temporarily: worth retrying. */
    CHANNEL_UNAVAILABLE,

    /** The provider rejected the message: retried, a later attempt may pass. */
    REJECTED_BY_PROVIDER,

    /** The recipient address is unusable: retrying cannot help. */
    INVALID_RECIPIENT,
}

/**
 * Why one delivery attempt failed: a [category] and a client-safe [reason] (shown to operators as `lastError`; it
 * never contains an address, a token or a message body).
 */
data class DeliveryFailure(
    val category: FailureCategory,
    val reason: String,
) {
    /** True when retrying cannot succeed. */
    val permanent: Boolean get() = category == FailureCategory.INVALID_RECIPIENT
}

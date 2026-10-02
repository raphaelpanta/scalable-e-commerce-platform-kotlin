package com.ecommerce.order.domain

/** Fulfilment status of an order (FR-015); [wire] is the contract value. */
enum class OrderStatus(
    val wire: String,
) {
    PLACED("placed"),
    PREPARING("preparing"),
    SHIPPED("shipped"),
    DELIVERED("delivered"),
    CANCELLED("cancelled"),
    ;

    /** `delivered` and `cancelled` end the lifecycle; the address snapshot of an anonymised account is scrubbed. */
    val isTerminal: Boolean get() = this == DELIVERED || this == CANCELLED

    companion object {
        /** The status named [wire] in a contract, if any. */
        fun fromWire(wire: String): OrderStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** Payment status of an order (FR-015): `pending` -> `approved` or `failed`. */
enum class PaymentStatus(
    val wire: String,
) {
    PENDING("pending"),
    APPROVED("approved"),
    FAILED("failed"),
    ;

    companion object {
        /** The status named [wire] in a contract, if any. */
        fun fromWire(wire: String): PaymentStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why an order was cancelled; recorded on every cancellation (FR-015). */
enum class CancellationReason {
    SHOPPER_REQUEST,
    OPERATOR,
    PAYMENT_FAILED,
    PAYMENT_EXPIRED,
}

/** Decline category surfaced to the shopper (service conventions section 8). */
enum class DeclineCategory(
    val wire: String,
) {
    INSUFFICIENT_FUNDS("insufficient_funds"),
    CARD_EXPIRED("card_expired"),
    CARD_REJECTED("card_rejected"),
    SUSPECTED_FRAUD("suspected_fraud"),
    INVALID_PAYMENT_METHOD("invalid_payment_method"),
    ;

    companion object {
        /** The category named [wire] in a contract, if any. */
        fun fromWire(wire: String): DeclineCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** Notification channel of a recipient snapshot. */
enum class Channel(
    val wire: String,
) {
    EMAIL("email"),
    SMS("sms"),
    ;

    companion object {
        /** The channel named [wire] in a contract, if any. */
        fun fromWire(wire: String): Channel? = entries.firstOrNull { it.wire == wire }
    }
}

/** Roles carried by an access token. */
enum class Role {
    SHOPPER,
    OPERATOR,
}

/** The authenticated caller of a use case: the token subject and its roles. */
data class Caller(
    val accountId: AccountId,
    val roles: Set<Role>,
) {
    val isShopper: Boolean get() = Role.SHOPPER in roles
    val isOperator: Boolean get() = Role.OPERATOR in roles
}

/** Who caused a status change (`by` of the status history). */
sealed interface Actor {
    /** The account id of the actor, or `system`. */
    val by: String

    /** A shopper acting on their own order. */
    data class Shopper(
        val accountId: AccountId,
    ) : Actor {
        override val by: String get() = accountId.toString()
    }

    /** An operator of the back office. */
    data class Operator(
        val accountId: AccountId,
    ) : Actor {
        override val by: String get() = accountId.toString()
    }

    /** The platform itself: payment outcomes, expiry. */
    data object System : Actor {
        const val BY: String = "system"
        override val by: String get() = BY
    }
}

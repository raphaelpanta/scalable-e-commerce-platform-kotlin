package com.ecommerce.payment.domain

/** What a payment provider answered to a charge (FR-014). */
sealed interface ProviderDecision {
    /** The attempt outcome this decision records. */
    val outcome: PaymentOutcome

    /** The provider's reference; none when the provider could not be reached. */
    val reference: ProviderReference?

    /** The provider approved the charge. */
    data class Approved(
        override val reference: ProviderReference,
    ) : ProviderDecision {
        override val outcome: PaymentOutcome get() = PaymentOutcome.APPROVED
    }

    /** The provider declined the charge for [category]. */
    data class Declined(
        val category: DeclineCategory,
        override val reference: ProviderReference,
    ) : ProviderDecision {
        override val outcome: PaymentOutcome get() = PaymentOutcome.DECLINED
    }

    /** The provider could not be reached: the attempt stays pending, which is never an error of the API. */
    data object Unreachable : ProviderDecision {
        override val outcome: PaymentOutcome get() = PaymentOutcome.PENDING
        override val reference: ProviderReference? get() = null
    }
}

/**
 * The payment provider behind a port (FR-014): the MVP adapter is the simulator whose deterministic rules are
 * [SimulatedPaymentRules]; a real provider later implements the same port without touching the order journey.
 */
interface PaymentProviderPort {
    /** Charges [amount] to [paymentMethod]. */
    suspend fun charge(
        paymentMethod: PaymentMethodRef,
        amount: Money,
    ): ProviderDecision

    /** Refunds [amount] of the charge the provider knows as [charge]; returns the refund's reference. */
    suspend fun refund(
        charge: ProviderReference,
        amount: Money,
    ): ProviderReference
}

/** The events of the payment context (`payment.payment.v1`), published through the outbox. */
sealed interface PaymentEvent {
    /** A charge attempt was recorded: `PaymentApproved`, `PaymentDeclined` or `PaymentPending` by its outcome. */
    data class ChargeRecorded(
        val attempt: PaymentAttempt,
    ) : PaymentEvent

    /** A refund was recorded: `RefundRecorded`, carrying the owner's contact snapshot for the notification. */
    data class RefundRecorded(
        val refund: RefundRecord,
        val recipient: Recipient,
    ) : PaymentEvent
}

package com.ecommerce.payment.application

import java.time.Clock
import java.time.Duration

/**
 * The bounded retention of the cancelled orders payment remembers (data-model sections 3.5 and 5, FR-007,
 * Constitution III): a remembered order exists so that a charge settling after the cancellation (a late approval) is
 * voided or refunded and its `RefundRecorded` reaches the shopper, so it is kept for the late-charge [retention] only
 * and then forgotten with its contact snapshot (email, phone). Run by a scheduled job; returns how many were forgotten.
 */
class PurgeCancelledOrders(
    private val cancellations: CancelledOrderRepository,
    private val clock: Clock,
    private val retention: Duration,
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "the cancelled-order retention is positive" }
    }

    suspend operator fun invoke(): Long = cancellations.forgetRecordedBefore(clock.instant().minus(retention))
}

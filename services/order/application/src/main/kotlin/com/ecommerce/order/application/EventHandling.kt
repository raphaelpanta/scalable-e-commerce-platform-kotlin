package com.ecommerce.order.application

import arrow.core.Either
import arrow.core.right
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.OrderError
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.order.domain.RefundId
import com.ecommerce.order.domain.anonymise
import com.ecommerce.order.domain.applyPayment
import com.ecommerce.order.domain.expirePayment
import com.ecommerce.order.domain.recordRefund
import java.time.Clock

/**
 * `PaymentApproved`, `PaymentDeclined`, `PaymentPending` (FR-015): applied only while the payment is pending, so
 * the event converges with the synchronous charge and a duplicate is never applied twice.
 */
class ApplyPaymentOutcome(
    private val store: OrderStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        orderId: OrderId,
        outcome: PaymentOutcome,
    ): Either<OrderError, Modification> = store.modify(orderId) { it.applyPayment(outcome, clock.instant()).right() }
}

/** `RefundRecorded`: records the refund reference of a cancelled, paid order once. */
class RecordRefund(
    private val store: OrderStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        orderId: OrderId,
        refundId: RefundId,
    ): Either<OrderError, Modification> = store.modify(orderId) { it.recordRefund(refundId, clock.instant()).right() }
}

/** `AccountDeleted` (FR-007): every retained order of the account goes under [pseudonym]; returns how many changed. */
class AnonymiseAccountOrders(
    private val store: OrderStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        pseudonym: String,
    ): Either<OrderError, Int> {
        var changed = 0
        store.orders.findAllByAccount(accountId).forEach { order ->
            when (val result = store.modify(order.id) { it.anonymise(pseudonym).right() }) {
                is Either.Left -> return result
                is Either.Right -> if (result.value.changed) changed++
            }
        }
        return changed.right()
    }
}

/**
 * The payment expiry job (FR-015): orders whose payment is still pending 30 minutes after placement are cancelled
 * with `PAYMENT_EXPIRED` (payment voided, `OrderCancelled`) and their stock is released. Returns how many expired.
 */
class ExpirePendingPayments(
    private val store: OrderStore,
    private val catalog: CatalogPort,
    private val clock: Clock,
) {
    suspend operator fun invoke(batchSize: Int): Int {
        val now = clock.instant()
        return store.orders.findExpiredPendingPayments(now, batchSize).count { order ->
            store
                .modify(order.id) { it.expirePayment(now).right() }
                .onRight { catalog.releaseIfCancelledBeforePayment(it) }
                .fold({ false }, { it.changed })
        }
    }
}

package com.ecommerce.payment.application

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.ecommerce.payment.domain.Caller
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.SimulatorRules

/** `getPaymentAttempt`: operators see any attempt, shoppers the attempts of their own orders; others get 404. */
class GetPaymentAttempt(
    private val attempts: PaymentAttemptRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        id: PaymentAttemptId,
    ): Either<PaymentError, PaymentAttempt> =
        attempts.findById(id)?.takeIf { caller.canSee(it.accountId) }?.right() ?: PaymentError.NotFound.left()
}

/** `listPaymentAttemptsForOrder`: the attempts of an order, newest first, for its owner or an operator. */
class ListPaymentAttemptsForOrder(
    private val attempts: PaymentAttemptRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        orderId: OrderId,
        page: PageRequest,
    ): Either<PaymentError, Page<PaymentAttempt>> =
        either {
            caller.canList(attempts.ownerOf(orderId)).bind()
            attempts.findByOrder(orderId, page)
        }
}

/** `listRefundsForOrder`: the refunds of an order, newest first, for its owner or an operator. */
class ListRefundsForOrder(
    private val attempts: PaymentAttemptRepository,
    private val refunds: RefundRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        orderId: OrderId,
        page: PageRequest,
    ): Either<PaymentError, Page<RefundRecord>> =
        either {
            caller.canList(attempts.ownerOf(orderId)).bind()
            refunds.findByOrder(orderId, page)
        }
}

/** `getRefund`: operators see any refund, shoppers the refunds of their own orders; others get 404. */
class GetRefund(
    private val refunds: RefundRepository,
) {
    suspend operator fun invoke(
        caller: Caller,
        id: RefundId,
    ): Either<PaymentError, RefundRecord> =
        refunds.findById(id)?.takeIf { caller.canSee(it.accountId) }?.right() ?: PaymentError.NotFound.left()
}

/** `getSimulatorRules`: the rule document of the simulated provider, for operators only (shoppers get 403). */
class GetSimulatorRules(
    private val rules: SimulatorRules,
) {
    operator fun invoke(caller: Caller): Either<PaymentError, SimulatorRules> =
        if (caller.isOperator) rules.right() else PaymentError.Forbidden.left()
}

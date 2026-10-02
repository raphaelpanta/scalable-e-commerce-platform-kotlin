package com.ecommerce.payment.infrastructure.persistence

import com.ecommerce.payment.application.PaymentIds
import com.ecommerce.payment.application.Transactions
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.RefundId
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID

/** One reactive R2DBC transaction; a nested call joins the transaction already running (an event consumer's). */
class R2dbcTransactions(
    private val operator: TransactionalOperator,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = operator.executeAndAwait { block() }
}

/** Random (version 4) identifiers for attempts and refunds. */
class RandomPaymentIds : PaymentIds {
    override fun nextAttempt(): PaymentAttemptId = PaymentAttemptId(UUID.randomUUID())

    override fun nextRefund(): RefundId = RefundId(UUID.randomUUID())
}

package com.ecommerce.order.infrastructure.persistence

import com.ecommerce.order.application.Transactions
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** One reactive R2DBC transaction; a nested call joins the transaction already running (an event consumer's). */
class R2dbcTransactions(
    private val operator: TransactionalOperator,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = operator.executeAndAwait { block() }
}

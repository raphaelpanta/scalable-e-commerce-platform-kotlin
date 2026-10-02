package com.ecommerce.cart.infrastructure.persistence

import arrow.core.Either
import com.ecommerce.cart.application.Transactions
import com.ecommerce.cart.domain.CartError
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** Outbound adapter: one R2DBC transaction per block, rolled back when the block returns an error (or throws). */
class ReactiveTransactions(
    private val operator: TransactionalOperator,
) : Transactions {
    override suspend fun <T> inTransaction(block: suspend () -> Either<CartError, T>): Either<CartError, T> =
        operator.executeAndAwait { status -> block().onLeft { status.setRollbackOnly() } }
}

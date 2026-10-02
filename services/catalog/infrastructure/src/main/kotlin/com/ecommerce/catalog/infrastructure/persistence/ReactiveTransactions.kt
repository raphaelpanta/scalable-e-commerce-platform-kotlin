package com.ecommerce.catalog.infrastructure.persistence

import arrow.core.Either
import com.ecommerce.catalog.application.Transactions
import com.ecommerce.catalog.domain.CatalogError
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** Outbound adapter: one R2DBC transaction per block, rolled back when the block returns an error (or throws). */
class ReactiveTransactions(
    private val operator: TransactionalOperator,
) : Transactions {
    override suspend fun <T> inTransaction(block: suspend () -> Either<CatalogError, T>): Either<CatalogError, T> =
        operator.executeAndAwait { status -> block().onLeft { status.setRollbackOnly() } }
}

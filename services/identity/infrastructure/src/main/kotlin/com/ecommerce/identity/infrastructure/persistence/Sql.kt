package com.ecommerce.identity.infrastructure.persistence

import arrow.core.Either
import com.ecommerce.identity.application.Transactions
import com.ecommerce.identity.domain.IdentityError
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** The non-null value of column [name]. */
internal inline fun <reified T : Any> Readable.required(name: String): T =
    checkNotNull(get(name, T::class.javaObjectType)) { "column $name is null" }

/** The value of column [name], or null. */
internal inline fun <reified T : Any> Readable.optional(name: String): T? = get(name, T::class.javaObjectType)

/** Binds [value], or a typed SQL NULL when it is null. */
internal fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

/** Binds every entry of [bindings] (a null value as a typed NULL is not supported here: use [bindNullable]). */
internal fun DatabaseClient.GenericExecuteSpec.bindAll(bindings: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
    bindings.entries.fold(this) { spec, (name, value) -> spec.bind(name, value) }

/** Outbound adapter: one R2DBC transaction per block, rolled back when the block returns an error (or throws). */
class ReactiveTransactions(
    private val operator: TransactionalOperator,
) : Transactions {
    override suspend fun <T> inTransaction(block: suspend () -> Either<IdentityError, T>): Either<IdentityError, T> =
        operator.executeAndAwait { status -> block().onLeft { status.setRollbackOnly() } }
}

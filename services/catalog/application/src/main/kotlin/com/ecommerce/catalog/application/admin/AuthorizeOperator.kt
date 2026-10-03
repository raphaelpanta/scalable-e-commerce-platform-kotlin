package com.ecommerce.catalog.application.admin

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.application.Catalog
import com.ecommerce.catalog.application.OperatorAction
import com.ecommerce.catalog.domain.AccountId
import com.ecommerce.catalog.domain.CatalogError
import java.util.UUID

/**
 * The operator check of every catalogue write, run by the web adapter before it reads the path or the body, so a
 * caller without the operator role is always refused (403) and audited, whatever the request holds (US7 scenario 4).
 * The use cases check again (defence in depth); for an operator the second check records nothing.
 */
class AuthorizeOperator(
    private val catalog: Catalog,
) {
    suspend operator fun invoke(
        caller: Caller,
        action: OperatorAction,
        target: UUID?,
    ): Either<CatalogError, AccountId> = catalog.authorize(caller, action, target)
}

/**
 * Runs [change] and records that [actor] performed [action] on [target] in one transaction, so a change is never
 * stored without its audit entry and a refused change leaves none.
 */
internal suspend fun <T> Catalog.auditedChange(
    actor: AccountId,
    action: OperatorAction,
    target: UUID,
    change: suspend Raise<CatalogError>.() -> T,
): Either<CatalogError, T> =
    transactions.inTransaction {
        either {
            val result = change()
            audited(actor, action, target)
            result
        }
    }

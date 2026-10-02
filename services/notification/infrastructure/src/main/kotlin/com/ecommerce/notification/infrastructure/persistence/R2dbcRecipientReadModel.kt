package com.ecommerce.notification.infrastructure.persistence

import com.ecommerce.notification.application.Clock
import com.ecommerce.notification.application.RecipientReadModel
import com.ecommerce.notification.application.Transactions
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.Recipient
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** [RecipientReadModel] on the `recipients` table. */
class R2dbcRecipientReadModel(
    private val database: DatabaseClient,
    private val clock: Clock,
) : RecipientReadModel {
    override suspend fun find(accountId: AccountId): Recipient? =
        database
            .sql("SELECT account_id, email_verified, anonymised FROM recipients WHERE account_id = :accountId")
            .bind("accountId", accountId.value)
            .map { row ->
                Recipient(
                    AccountId(row.required("account_id")),
                    row.required("email_verified"),
                    row.required("anonymised"),
                )
            }.one()
            .awaitSingleOrNull()

    override suspend fun save(recipient: Recipient) {
        database
            .sql(
                "INSERT INTO recipients (account_id, email_verified, anonymised, updated_at) " +
                    "VALUES (:accountId, :verified, :anonymised, :at) ON CONFLICT (account_id) DO UPDATE SET " +
                    "email_verified = EXCLUDED.email_verified, anonymised = EXCLUDED.anonymised, " +
                    "updated_at = EXCLUDED.updated_at",
            ).bind("accountId", recipient.accountId.value)
            .bind("verified", recipient.emailVerified)
            .bind("anonymised", recipient.anonymised)
            .bind("at", clock.now())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}

/** [Transactions] through the reactive transaction manager of the service's connection factory. */
class R2dbcTransactions(
    private val operator: TransactionalOperator,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = operator.executeAndAwait { block() }
}

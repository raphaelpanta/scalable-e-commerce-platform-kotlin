package com.ecommerce.identity.infrastructure.persistence

import com.ecommerce.identity.application.AccountRepository
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.DisplayName
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.domain.Pseudonym
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.SignInThrottle
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant
import java.util.UUID

private const val COLUMNS =
    "id, email, password_hash, status, roles, display_name, created_at, verified_at, failed_sign_ins, " +
        "locked_until, deleted_at, pseudonym, version"

/** Outbound adapter: accounts in the `account` table (V2__identity_schema.sql), optimistic locking on `version`. */
class R2dbcAccountRepository(
    private val database: DatabaseClient,
) : AccountRepository {
    override suspend fun findById(id: AccountId): Account? =
        database
            .sql("SELECT $COLUMNS FROM account WHERE id = :id")
            .bind("id", id.value)
            .map(::accountOf)
            .awaitOneOrNull()

    override suspend fun findByEmail(email: Email): Account? =
        database
            .sql("SELECT $COLUMNS FROM account WHERE email = :email AND status <> 'deleted'")
            .bind("email", email.value)
            .map(::accountOf)
            .awaitOneOrNull()

    // ON CONFLICT DO NOTHING: a concurrent registration of the same email inserts nothing and keeps the
    // transaction usable (a unique violation would abort it).
    override suspend fun insert(account: Account): Boolean =
        database
            .sql(
                "INSERT INTO account ($COLUMNS) VALUES (:id, :email, :passwordHash, :status, :roles, :displayName, " +
                    ":createdAt, :verifiedAt, :failedSignIns, :lockedUntil, :deletedAt, :pseudonym, :version) " +
                    "ON CONFLICT DO NOTHING",
            ).bindColumns(account)
            .fetch()
            .awaitRowsUpdated() == 1L

    override suspend fun update(
        account: Account,
        expectedVersion: Long,
    ): Boolean =
        database
            .sql(
                "UPDATE account SET email = :email, password_hash = :passwordHash, status = :status, roles = :roles, " +
                    "display_name = :displayName, created_at = :createdAt, verified_at = :verifiedAt, " +
                    "failed_sign_ins = :failedSignIns, locked_until = :lockedUntil, deleted_at = :deletedAt, " +
                    "pseudonym = :pseudonym, version = :version WHERE id = :id AND version = :expected",
            ).bindColumns(account)
            .bind("expected", expectedVersion)
            .fetch()
            .awaitRowsUpdated() == 1L

    private fun DatabaseClient.GenericExecuteSpec.bindColumns(account: Account): DatabaseClient.GenericExecuteSpec =
        bind("id", account.id.value)
            .bind("email", account.email.value)
            .bindNullable("passwordHash", account.passwordHash?.value, String::class.java)
            .bind("status", account.status.code)
            .bind("roles", rolesColumn(account.roles))
            .bindNullable("displayName", account.displayName?.value, String::class.java)
            .bind("createdAt", account.createdAt)
            .bindNullable("verifiedAt", account.verifiedAt, Instant::class.java)
            .bind("failedSignIns", account.throttle.failures)
            .bindNullable("lockedUntil", account.throttle.lockedUntil, Instant::class.java)
            .bindNullable("deletedAt", account.deletedAt, Instant::class.java)
            .bindNullable("pseudonym", account.pseudonym?.value, String::class.java)
            .bind("version", account.version)

    companion object {
        fun rolesColumn(roles: Set<Role>): String = roles.sortedBy { it.ordinal }.joinToString(",") { it.code }

        fun accountOf(row: Readable): Account =
            Account(
                id = AccountId(row.required<UUID>("id")),
                email = Email.of(row.required("email")).fold({ error("stored email: ${it.reason}") }, { it }),
                passwordHash = row.optional<String>("password_hash")?.let(::PasswordHash),
                status = checkNotNull(AccountStatus.fromCode(row.required("status"))) { "stored status" },
                roles =
                    row
                        .required<String>("roles")
                        .split(',')
                        .mapNotNull(Role::fromCode)
                        .toSet(),
                displayName = DisplayName.of(row.optional<String>("display_name")).getOrNull(),
                createdAt = row.required("created_at"),
                verifiedAt = row.optional("verified_at"),
                throttle = SignInThrottle(row.required("failed_sign_ins"), row.optional("locked_until")),
                deletedAt = row.optional("deleted_at"),
                pseudonym = row.optional<String>("pseudonym")?.let(::Pseudonym),
                version = row.required("version"),
            )
    }
}

package com.ecommerce.identity.infrastructure.persistence

import com.ecommerce.identity.application.AddressRepository
import com.ecommerce.identity.application.PreferenceRepository
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.Address
import com.ecommerce.identity.domain.AddressBook
import com.ecommerce.identity.domain.AddressId
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.PhoneNumber
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.PostalAddress
import com.ecommerce.identity.domain.TokenHash
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID

/**
 * Outbound adapter: the `address` table. A book is saved as a whole (at most 10 rows) in one transaction, which
 * joins the caller's transaction when there is one.
 */
class R2dbcAddressRepository(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
) : AddressRepository {
    override suspend fun book(accountId: AccountId): AddressBook =
        AddressBook(
            accountId,
            database
                .sql(
                    "SELECT id, label, recipient_name, line1, line2, city, region, postal_code, country_code, " +
                        "is_default FROM address WHERE account_id = :accountId ORDER BY position",
                ).bind("accountId", accountId.value)
                .map(::addressOf)
                .flow()
                .toList(),
        )

    override suspend fun save(book: AddressBook) {
        transactions.executeAndAwait {
            database
                .sql("DELETE FROM address WHERE account_id = :accountId")
                .bind("accountId", book.accountId.value)
                .fetch()
                .awaitRowsUpdated()
            book.addresses.forEachIndexed { position, address -> insert(book.accountId, position, address) }
        }
    }

    private suspend fun insert(
        accountId: AccountId,
        position: Int,
        address: Address,
    ) {
        val postal = address.postal
        database
            .sql(
                "INSERT INTO address (id, account_id, position, label, recipient_name, line1, line2, city, region, " +
                    "postal_code, country_code, is_default) VALUES (:id, :accountId, :position, :label, " +
                    ":recipientName, :line1, :line2, :city, :region, :postalCode, :countryCode, :isDefault)",
            ).bind("id", address.id.value)
            .bind("accountId", accountId.value)
            .bind("position", position)
            .bindNullable("label", address.label, String::class.java)
            .bind("recipientName", postal.recipientName)
            .bind("line1", postal.line1)
            .bindNullable("line2", postal.line2, String::class.java)
            .bind("city", postal.city)
            .bindNullable("region", postal.region, String::class.java)
            .bind("postalCode", postal.postalCode)
            .bind("countryCode", postal.countryCode)
            .bind("isDefault", address.isDefault)
            .fetch()
            .awaitRowsUpdated()
    }

    private companion object {
        fun addressOf(row: Readable): Address {
            val fields =
                PostalAddress.Fields(
                    row.required("recipient_name"),
                    row.required("line1"),
                    row.optional("line2"),
                    row.required("city"),
                    row.optional("region"),
                    row.required("postal_code"),
                    row.required("country_code"),
                )
            return Address(
                AddressId(row.required<UUID>("id")),
                row.optional("label"),
                PostalAddress.validate(fields).fold({ error("stored address: $it") }, { it }),
                row.required("is_default"),
            )
        }
    }
}

/** Outbound adapter: the `notification_preference` and `phone_verification` tables. */
class R2dbcPreferenceRepository(
    private val database: DatabaseClient,
) : PreferenceRepository {
    override suspend fun find(accountId: AccountId): NotificationPreference? =
        database
            .sql("SELECT channels, phone_number, phone_verified FROM notification_preference WHERE account_id = :id")
            .bind("id", accountId.value)
            .map { row ->
                NotificationPreference(
                    accountId,
                    channelsOf(row.required("channels")),
                    row.optional<String>("phone_number")?.let(::phoneOf),
                    row.required("phone_verified"),
                )
            }.awaitOneOrNull()

    override suspend fun save(preference: NotificationPreference) {
        database
            .sql(
                "INSERT INTO notification_preference (account_id, channels, phone_number, phone_verified) " +
                    "VALUES (:id, :channels, :phone, :verified) ON CONFLICT (account_id) DO UPDATE SET " +
                    "channels = EXCLUDED.channels, phone_number = EXCLUDED.phone_number, " +
                    "phone_verified = EXCLUDED.phone_verified",
            ).bind("id", preference.accountId.value)
            .bind("channels", channelsColumn(preference.channels))
            .bindNullable("phone", preference.phone?.value, String::class.java)
            .bind("verified", preference.phoneVerified)
            .fetch()
            .awaitRowsUpdated()
    }

    override suspend fun pendingVerification(accountId: AccountId): PhoneVerification? =
        database
            .sql(
                "SELECT phone_number, code_hash, issued_at, expires_at, attempts FROM phone_verification " +
                    "WHERE account_id = :id",
            ).bind("id", accountId.value)
            .map { row ->
                PhoneVerification(
                    accountId,
                    phoneOf(row.required("phone_number")),
                    TokenHash(row.required("code_hash")),
                    row.required("issued_at"),
                    row.required("expires_at"),
                    row.required("attempts"),
                )
            }.awaitOneOrNull()

    override suspend fun saveVerification(verification: PhoneVerification) {
        database
            .sql(
                "INSERT INTO phone_verification (account_id, phone_number, code_hash, issued_at, expires_at, " +
                    "attempts) VALUES (:id, :phone, :hash, :issuedAt, :expiresAt, :attempts) " +
                    "ON CONFLICT (account_id) DO UPDATE SET phone_number = EXCLUDED.phone_number, " +
                    "code_hash = EXCLUDED.code_hash, issued_at = EXCLUDED.issued_at, " +
                    "expires_at = EXCLUDED.expires_at, attempts = EXCLUDED.attempts",
            ).bind("id", verification.accountId.value)
            .bind("phone", verification.phone.value)
            .bind("hash", verification.codeHash.value)
            .bind("issuedAt", verification.issuedAt)
            .bind("expiresAt", verification.expiresAt)
            .bind("attempts", verification.attempts)
            .fetch()
            .awaitRowsUpdated()
    }

    override suspend fun deleteVerification(accountId: AccountId) {
        database
            .sql("DELETE FROM phone_verification WHERE account_id = :id")
            .bind("id", accountId.value)
            .fetch()
            .awaitRowsUpdated()
    }

    companion object {
        fun channelsColumn(channels: Set<NotificationChannel>): String =
            channels.sortedBy { it.ordinal }.joinToString(",") { it.code }

        fun channelsOf(column: String): Set<NotificationChannel> =
            column.split(',').mapNotNull(NotificationChannel::fromCode).toSet()

        private fun phoneOf(raw: String): PhoneNumber = PhoneNumber.of(raw).fold({ error("stored phone") }, { it })
    }
}

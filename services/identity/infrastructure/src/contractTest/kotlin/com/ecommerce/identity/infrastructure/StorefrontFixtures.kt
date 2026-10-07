package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.Digests
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.TokenPurpose
import com.ecommerce.identity.domain.VerificationCode
import com.ecommerce.identity.infrastructure.security.Argon2idPasswordHasher
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The fixtures the storefront's provider states name (pact-matrix.md, "Storefront to identity", I1 to I18): the
 * accounts, the secrets the consumer sends as fixed values (tokens, password) and the ids its examples use. Test-only
 * values; the password hash is computed once per JVM (Argon2id is deliberately slow).
 */
internal object Storefront {
    const val ANA_EMAIL = "ana@example.com"
    const val ANA_NAME = "Ana Silva"
    const val OPS_EMAIL = "ops@example.com"
    const val NEW_EMAIL = "new@example.com"
    const val PASSWORD = "S3cure-passphrase!"

    /** The `id` of the consumer's `Account` example (`ADA` in `frontend/pact/gateway.ts`). */
    val ANA_ID: UUID = UUID.fromString("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d")
    val OPS_ID: UUID = UUID.fromString("0b7e2c9d-5f31-4a8e-9c6d-2e4f6a8b0c1d")
    val SESSION_ID: UUID = UUID.fromString("3e9a7c1b-6d2f-4b8a-a1c3-5d7e9f0b2a4c")

    /** The `createdAt` of the consumer's `Account` example. */
    val CREATED_AT: Instant = Instant.parse("2026-10-02T09:15:00Z")

    /** The address ids of the consumer's examples (`ADDRESS_2`, `ADDRESS_OWNED`). */
    val HOME_ADDRESS_ID: UUID = UUID.fromString("7a1c4e52-90b3-4d6f-8e21-5c3d9f0a1b22")
    val WORK_ADDRESS_ID: UUID = UUID.fromString("5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11")

    /** The token values the consumer sends verbatim (I4, I5, I18). */
    const val VERIFICATION_TOKEN = "tok-valid"
    const val EXPIRED_TOKEN = "tok-expired"
    const val RESET_TOKEN = "tok-reset"
    const val REFRESH_TOKEN = "9b8d6c1a-opaque-refresh-token"

    const val PHONE = "+351912345678"
    const val PHONE_CODE = "123456"
    const val EMAIL_CHANNEL = "email"

    /** `ana@example.com has 5 failed sign-ins`: locked, and the consumer expects `Retry-After: 60`. */
    const val FAILED_SIGN_INS = 5
    val RETRY_AFTER: Duration = Duration.ofSeconds(60)

    val PASSWORD_HASH: String by lazy { Argon2idPasswordHasher().encode(PASSWORD) }
}

/** A nullable binding: R2DBC needs the column type to bind a null. */
private class Nullable(
    val value: Any?,
    val type: Class<*>,
)

private fun text(value: String?): Nullable = Nullable(value, String::class.java)

private fun time(value: Instant?): Nullable = Nullable(value, Instant::class.java)

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * Writes the rows a storefront provider state describes, replacing whatever an earlier interaction left behind
 * (accounts are recreated, so their addresses, preferences, tokens and sessions go with them).
 */
internal class StorefrontSeeds(
    private val database: DatabaseClient,
) {
    /** Recreates account [id] with [email]; the sign-in throttles of every source address are cleared with it. */
    @Suppress("LongParameterList") // one argument per column a state may set
    fun account(
        id: UUID,
        email: String,
        status: AccountStatus,
        role: Role = Role.SHOPPER,
        displayName: String? = null,
        passwordHash: String? = null,
        throttle: SignInThrottle = SignInThrottle.CLEAR,
    ) {
        execute("DELETE FROM account WHERE id = :id OR email = :email", mapOf("id" to id, "email" to email))
        execute("DELETE FROM sign_in_source", emptyMap())
        execute(
            "INSERT INTO account (id, email, password_hash, status, roles, display_name, created_at, verified_at, " +
                "failed_sign_ins, locked_until, deleted_at, pseudonym, version) VALUES (:id, :email, :hash, " +
                ":status, :roles, :displayName, :createdAt, :verifiedAt, :failures, :lockedUntil, NULL, NULL, 0)",
            mapOf(
                "id" to id,
                "email" to email,
                "hash" to text(passwordHash),
                "status" to status.code,
                "roles" to role.code,
                "displayName" to text(displayName),
                "createdAt" to Storefront.CREATED_AT,
                "verifiedAt" to time(Storefront.CREATED_AT.takeIf { status == AccountStatus.ACTIVE }),
                "failures" to throttle.failures,
                "lockedUntil" to time(throttle.lockedUntil),
            ),
        )
    }

    fun deleteAccount(email: String) {
        execute("DELETE FROM account WHERE email = :email", mapOf("email" to email))
    }

    /** An unused one-time [token] of [purpose] for [accountId], stored by hash, valid until [expiresAt]. */
    fun oneTimeToken(
        accountId: UUID,
        purpose: TokenPurpose,
        token: String,
        expiresAt: Instant,
    ) {
        val hash = Digests.sha256Hex(token)
        execute("DELETE FROM one_time_token WHERE token_hash = :hash", mapOf("hash" to hash))
        execute(
            "INSERT INTO one_time_token (token_hash, account_id, purpose, issued_at, expires_at, used_at) " +
                "VALUES (:hash, :accountId, :purpose, :issuedAt, :expiresAt, NULL)",
            mapOf(
                "hash" to hash,
                "accountId" to accountId,
                "purpose" to purpose.code,
                "issuedAt" to expiresAt.minus(purpose.ttl),
                "expiresAt" to expiresAt,
            ),
        )
    }

    /** A live session [sessionId] of [accountId], started at [now], whose current refresh token is [refreshToken]. */
    fun session(
        accountId: UUID,
        sessionId: UUID,
        refreshToken: String,
        now: Instant,
        lifetime: Duration,
    ) {
        val hash = Digests.sha256Hex(refreshToken)
        execute("DELETE FROM account_session WHERE id = :id", mapOf("id" to sessionId))
        execute("DELETE FROM session_refresh_token WHERE token_hash = :hash", mapOf("hash" to hash))
        execute(
            "INSERT INTO account_session (id, account_id, refresh_token_hash, issued_at, expires_at, rotated_at, " +
                "revoked_at) VALUES (:id, :accountId, :hash, :issuedAt, :expiresAt, NULL, NULL)",
            mapOf(
                "id" to sessionId,
                "accountId" to accountId,
                "hash" to hash,
                "issuedAt" to now,
                "expiresAt" to now.plus(lifetime),
            ),
        )
        execute(
            "INSERT INTO session_refresh_token (token_hash, session_id) VALUES (:hash, :id)",
            mapOf("hash" to hash, "id" to sessionId),
        )
    }

    /** The labelled addresses of [accountId] (id to label), in order; the first one is the default. */
    fun addresses(
        accountId: UUID,
        vararg labelled: Pair<UUID, String>,
    ) {
        execute("DELETE FROM address WHERE account_id = :accountId", mapOf("accountId" to accountId))
        labelled.forEachIndexed { position, (id, label) ->
            execute(
                "INSERT INTO address (id, account_id, position, label, recipient_name, line1, line2, city, region, " +
                    "postal_code, country_code, is_default) VALUES (:id, :accountId, :position, :label, " +
                    "'Ana Silva', 'Rua das Flores 12', NULL, 'Lisboa', NULL, '1000-001', 'PT', :isDefault)",
                mapOf(
                    "id" to id,
                    "accountId" to accountId,
                    "position" to position,
                    "label" to label,
                    "isDefault" to (position == 0),
                ),
            )
        }
    }

    /** The notification preferences of [accountId]: the comma-separated [channels] and the phone, if any. */
    fun preference(
        accountId: UUID,
        channels: String,
        phone: String? = null,
        phoneVerified: Boolean = false,
    ) {
        execute(
            "INSERT INTO notification_preference (account_id, channels, phone_number, phone_verified) " +
                "VALUES (:id, :channels, :phone, :verified) ON CONFLICT (account_id) DO UPDATE SET " +
                "channels = EXCLUDED.channels, phone_number = EXCLUDED.phone_number, " +
                "phone_verified = EXCLUDED.phone_verified",
            mapOf("id" to accountId, "channels" to channels, "phone" to text(phone), "verified" to phoneVerified),
        )
    }

    /** A pending verification of [phone] for [accountId] with the 6-digit [code], issued at [issuedAt]. */
    fun phoneVerification(
        accountId: UUID,
        phone: String,
        code: String,
        issuedAt: Instant,
        ttl: Duration,
    ) {
        val hash = VerificationCode.of(code).fold({ error(it.reason) }, { it }).hashFor(AccountId(accountId))
        execute(
            "INSERT INTO phone_verification (account_id, phone_number, code_hash, issued_at, expires_at, attempts) " +
                "VALUES (:id, :phone, :hash, :issuedAt, :expiresAt, 0) ON CONFLICT (account_id) DO UPDATE SET " +
                "phone_number = EXCLUDED.phone_number, code_hash = EXCLUDED.code_hash, " +
                "issued_at = EXCLUDED.issued_at, expires_at = EXCLUDED.expires_at, attempts = 0",
            mapOf(
                "id" to accountId,
                "phone" to phone,
                "hash" to hash.value,
                "issuedAt" to issuedAt,
                "expiresAt" to issuedAt.plus(ttl),
            ),
        )
    }

    private fun execute(
        sql: String,
        bindings: Map<String, Any>,
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) ->
                when {
                    value !is Nullable -> spec.bind(name, value)
                    value.value == null -> spec.bindNull(name, value.type)
                    else -> spec.bind(name, value.value)
                }
            }.fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }
}

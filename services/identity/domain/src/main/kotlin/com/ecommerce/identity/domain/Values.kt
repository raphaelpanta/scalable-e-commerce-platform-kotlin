package com.ecommerce.identity.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

private const val MASK = "***"

/**
 * An email address (data-model section 2): trimmed and lower-cased, so comparisons ignore case; at most 254
 * characters, exactly one `@`, a non-empty local part and a domain with an inner dot, no whitespace or control
 * characters. Personal data: `toString()` masks the local part.
 */
@JvmInline
value class Email private constructor(
    val value: String,
) {
    override fun toString(): String = value.first() + MASK + value.substring(value.indexOf('@'))

    companion object {
        const val MAX_LENGTH: Int = 254

        fun of(
            raw: String,
            field: String = "email",
        ): Either<FieldError, Email> {
            val value = raw.trim().lowercase(Locale.ROOT)
            val local = value.substringBefore('@')
            val domain = value.substringAfter('@', missingDelimiterValue = "")
            val reason =
                when {
                    value.isEmpty() -> "must not be blank"
                    value.length > MAX_LENGTH -> "must be at most $MAX_LENGTH characters"
                    value.any { it.isWhitespace() || it.isISOControl() } -> "must not contain whitespace"
                    value.count { it == '@' } != 1 -> "must contain exactly one @"
                    local.isEmpty() -> "must have a local part before the @"
                    !hasDottedDomain(domain) -> "must have a domain containing a dot"
                    else -> null
                }
            return if (reason == null) Email(value).right() else FieldError(field, reason).left()
        }

        private fun hasDottedDomain(domain: String): Boolean =
            domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }
}

/**
 * A new password that meets the policy (data-model section 1, FR-004): 12 to 128 characters and not equal to the
 * account's email address (ignoring case). Input only: never stored, never logged (`toString()` is masked).
 */
class Password private constructor(
    val value: String,
) {
    override fun toString(): String = "Password($MASK)"

    companion object {
        const val MIN_LENGTH: Int = 12
        const val MAX_LENGTH: Int = 128

        /** Checks [raw] against the policy; the email rule applies only when [email] is known. */
        fun of(
            raw: String,
            email: Email?,
            field: String = "password",
        ): Either<FieldError, Password> =
            when {
                raw.length < MIN_LENGTH -> {
                    FieldError(field, "must be at least $MIN_LENGTH characters").left()
                }

                raw.length > MAX_LENGTH -> {
                    FieldError(field, "must be at most $MAX_LENGTH characters").left()
                }

                email != null && raw.trim().equals(email.value, ignoreCase = true) -> {
                    FieldError(field, "must not be the email address").left()
                }

                else -> {
                    Password(raw).right()
                }
            }
    }
}

/** Output of the slow adaptive password hash (Argon2id, PHC string format); opaque and never logged. */
@JvmInline
value class PasswordHash(
    val value: String,
) {
    override fun toString(): String = "PasswordHash($MASK)"
}

/** The optional display name of a profile: trimmed, 1 to 100 characters, no control characters. */
@JvmInline
value class DisplayName private constructor(
    val value: String,
) {
    override fun toString(): String = value.first() + MASK

    companion object {
        const val MAX_LENGTH: Int = 100

        /** A blank or absent [raw] is no display name (`null`). */
        fun of(
            raw: String?,
            field: String = "displayName",
        ): Either<FieldError, DisplayName?> {
            val value = raw?.trim().orEmpty()
            return when {
                value.isEmpty() -> null.right()
                value.length > MAX_LENGTH -> FieldError(field, "must be at most $MAX_LENGTH characters").left()
                value.any { it.isISOControl() } -> FieldError(field, "must not contain control characters").left()
                else -> DisplayName(value).right()
            }
        }
    }
}

/** A phone number in E.164 form: `+`, a non-zero first digit, 8 to 15 digits. `toString()` shows two digits only. */
@JvmInline
value class PhoneNumber private constructor(
    val value: String,
) {
    override fun toString(): String = MASK + value.takeLast(2)

    companion object {
        private val E164 = Regex("\\+[1-9][0-9]{7,14}")

        fun of(
            raw: String,
            field: String = "phoneNumber",
        ): Either<FieldError, PhoneNumber> {
            val value = raw.trim()
            return if (E164.matches(value)) {
                PhoneNumber(value).right()
            } else {
                FieldError(field, "must be in E.164 format (+ and 8 to 15 digits)").left()
            }
        }
    }
}

/** A role of an account (FR-005), as named in the `roles` claim. */
enum class Role(
    val code: String,
) {
    SHOPPER("shopper"),
    OPERATOR("operator"),
    ;

    companion object {
        fun fromCode(code: String): Role? = entries.firstOrNull { it.code == code }
    }
}

/** Lifecycle of an account (data-model section 3.1): unverified until the email is verified, deleted is terminal. */
enum class AccountStatus(
    val code: String,
) {
    UNVERIFIED("unverified"),
    ACTIVE("active"),
    DELETED("deleted"),
    ;

    companion object {
        fun fromCode(code: String): AccountStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A channel notifications may use. */
enum class NotificationChannel(
    val code: String,
) {
    EMAIL("email"),
    SMS("sms"),
    ;

    companion object {
        fun fromCode(code: String): NotificationChannel? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The stable pseudonym that replaces an account in retained records once it is deleted (data-model section 1,
 * `AccountPseudonym`): `anon-` and the first 8 hex digits of the SHA-256 of the account id, so it cannot be reversed
 * without the id.
 */
@JvmInline
value class Pseudonym(
    val value: String,
) {
    /** The unusable placeholder address of an anonymised account (`.invalid` is a reserved, undeliverable TLD). */
    val placeholderEmail: String get() = "$value@$ANONYMISED_DOMAIN"

    companion object {
        const val PREFIX: String = "anon-"
        const val ANONYMISED_DOMAIN: String = "anonymised.invalid"
        private const val DIGITS = 8

        fun of(accountId: AccountId): Pseudonym =
            Pseudonym(PREFIX + Digests.sha256Hex(accountId.value.toString()).take(DIGITS))
    }
}

/** Lower-case hex SHA-256 of a secret: the only form in which tokens and codes are stored. */
@JvmInline
value class TokenHash(
    val value: String,
)

/**
 * An opaque secret handed to a client once (verification, reset and refresh tokens): 43 url-safe Base64 characters
 * (256 random bits). Only its [hash] is stored; `toString()` never reveals it.
 */
class OpaqueToken private constructor(
    val value: String,
) {
    fun hash(): TokenHash = TokenHash(Digests.sha256Hex(value))

    override fun toString(): String = "OpaqueToken($MASK)"

    override fun equals(other: Any?): Boolean = other is OpaqueToken && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val LENGTH: Int = 43
        private val FORMAT = Regex("[A-Za-z0-9_-]{$LENGTH}")

        fun of(
            raw: String,
            field: String = "token",
        ): Either<FieldError, OpaqueToken> =
            if (FORMAT.matches(raw)) OpaqueToken(raw).right() else FieldError(field, "is not a valid token").left()
    }
}

/** The 6-digit one-time code of a phone verification; `toString()` never reveals it. */
class VerificationCode private constructor(
    val value: String,
) {
    /** The stored form: SHA-256 of the code bound to [accountId], so equal codes of two accounts differ. */
    fun hashFor(accountId: AccountId): TokenHash = TokenHash(Digests.sha256Hex("${accountId.value}:$value"))

    override fun toString(): String = "VerificationCode($MASK)"

    companion object {
        const val DIGITS: Int = 6
        private val FORMAT = Regex("[0-9]{$DIGITS}")

        fun of(
            raw: String,
            field: String = "code",
        ): Either<FieldError, VerificationCode> =
            if (FORMAT.matches(raw)) {
                VerificationCode(raw).right()
            } else {
                FieldError(field, "must be $DIGITS digits").left()
            }
    }
}

/** SHA-256 helpers of the domain. */
object Digests {
    fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** Compares two hashes without short-circuiting on the first difference. */
    fun sameHash(
        a: TokenHash,
        b: TokenHash,
    ): Boolean = MessageDigest.isEqual(a.value.toByteArray(Charsets.UTF_8), b.value.toByteArray(Charsets.UTF_8))
}

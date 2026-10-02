package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.observability.Pii
import com.ecommerce.platform.observability.PiiMasking
import java.util.Locale

/**
 * An email address, trimmed and lower-cased so that comparison is case-insensitive (data-model section 2): at most
 * 254 characters, exactly one `@`, a non-empty local part and a domain with an inner dot, no whitespace or control
 * characters. Personal data: `toString()` is masked (`a***@example.com`); use [value] only where the address is
 * really needed (persistence, the mail sender).
 */
@Pii
@JvmInline
value class Email private constructor(
    val value: String,
) {
    /** The part after the `@`. */
    val domain: String get() = value.substringAfter('@')

    override fun toString(): String = PiiMasking.maskEmail(value)

    companion object {
        const val MAX_LENGTH: Int = 254

        /** Normalises and validates [raw]. */
        fun of(
            raw: String,
            field: String = "email",
        ): Validated<Email> {
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
            return if (reason == null) Email(value).right() else ValidationError(field, reason).left()
        }

        private fun hasDottedDomain(domain: String): Boolean =
            domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }
}

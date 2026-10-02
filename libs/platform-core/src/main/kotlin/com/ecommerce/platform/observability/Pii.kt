package com.ecommerce.platform.observability

/**
 * Marks a type, property or parameter that holds personal data (constitution Principle III: email addresses, phone
 * numbers, postal addresses, names) or a secret. Marked values must never reach logs, traces or metrics in clear:
 * their `toString()` goes through [PiiMasking], and log statements pass the value object itself, never its raw
 * string. `PiiMaskingSpec` (T117) asserts this for the shared value objects.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Pii

/** Masking rules shared by every `@Pii` value's `toString()`; the masks never reveal the length of the value. */
object PiiMasking {
    /** The mask that replaces hidden characters. */
    const val MASK: String = "***"

    /**
     * Keeps the first character and masks the rest (`Ada Lovelace` -> `A***`); blank or one-character values are
     * fully masked, `null` stays `null`.
     */
    fun mask(value: String?): String? =
        when {
            value == null -> null
            value.length < 2 || value.isBlank() -> MASK
            else -> value.first() + MASK
        }

    /** Masks the local part of an email address and keeps the domain (`ada@example.com` -> `a***@example.com`). */
    fun maskEmail(value: String): String {
        val at = value.lastIndexOf('@')
        return if (at < 0) MASK else mask(value.substring(0, at)) + value.substring(at)
    }

    /** Masks all but the last [visible] characters (`+5511987654321` -> `***21`). */
    fun maskAllButLast(
        value: String,
        visible: Int,
    ): String = if (value.length <= visible) MASK else MASK + value.takeLast(visible)
}

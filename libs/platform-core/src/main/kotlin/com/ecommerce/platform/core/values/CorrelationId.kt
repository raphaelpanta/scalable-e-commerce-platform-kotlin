package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError
import java.util.UUID

/**
 * The id that ties together every log line, span and event of one request: 1 to 64 characters of `[A-Za-z0-9-]`
 * (data-model section 2; a UUID qualifies). An inbound value that is missing or malformed is replaced by a fresh
 * UUID ([sanitise]); the rejected original is kept, made log-safe, for the access log (FR-025).
 */
@JvmInline
value class CorrelationId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    /**
     * The outcome of [sanitise]: the [value] to use and, when an inbound value was present but rejected, its
     * log-safe [original] (`null` when the inbound value was accepted or absent).
     */
    data class Sanitised(
        val value: CorrelationId,
        val original: String?,
    ) {
        /** True when an inbound value was present but malformed and therefore replaced. */
        val replaced: Boolean get() = original != null
    }

    companion object {
        const val MAX_LENGTH: Int = 64

        /** Longest prefix of a rejected value kept for the log. */
        const val ORIGINAL_LOG_LENGTH: Int = 128
        private val ACCEPTED = Regex("[A-Za-z0-9-]{1,$MAX_LENGTH}")
        private const val FIRST_PRINTABLE = ' '
        private const val LAST_PRINTABLE = '~'
        private const val UNPRINTABLE = '?'

        /** Accepts 1..64 characters of `[A-Za-z0-9-]`. */
        fun of(
            raw: String,
            field: String = "correlationId",
        ): Validated<CorrelationId> =
            if (ACCEPTED.matches(raw)) {
                CorrelationId(raw).right()
            } else {
                ValidationError(field, "must be 1 to $MAX_LENGTH letters, digits or hyphens").left()
            }

        /** A new random (UUID) correlation id. */
        fun generate(): CorrelationId = CorrelationId(UUID.randomUUID().toString())

        /**
         * The correlation id to use for an inbound [raw] header value: the value itself when acceptable, otherwise a
         * generated one; a present but rejected value is returned as [Sanitised.original], truncated to
         * [ORIGINAL_LOG_LENGTH] characters with every non-printable character replaced by `?` (no log injection).
         */
        fun sanitise(raw: String?): Sanitised =
            when {
                raw.isNullOrEmpty() -> Sanitised(generate(), null)
                ACCEPTED.matches(raw) -> Sanitised(CorrelationId(raw), null)
                else -> Sanitised(generate(), logSafe(raw))
            }

        private fun logSafe(raw: String): String = raw.take(ORIGINAL_LOG_LENGTH).map(::printable).joinToString("")

        private fun printable(char: Char): Char = if (char in FIRST_PRINTABLE..LAST_PRINTABLE) char else UNPRINTABLE
    }
}

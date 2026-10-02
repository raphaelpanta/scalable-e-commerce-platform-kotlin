package com.ecommerce.catalog.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.Locale

/** Length and character rules shared by the text value objects; reasons are client-safe and name the limit. */
internal object Text {
    private val LINE_BREAKS = setOf('\n', '\r', '\t')

    /** The trimmed [raw] when it has [min]..[max] characters and no control character (line breaks if [multiline]). */
    fun bounded(
        raw: String,
        field: String,
        range: IntRange,
        multiline: Boolean = false,
    ): Either<FieldIssue, String> {
        val value = raw.trim()
        return when {
            value.length < range.first -> {
                FieldIssue(field, tooShort(range.first)).left()
            }

            value.length > range.last -> {
                FieldIssue(field, "must be at most ${range.last} characters").left()
            }

            value.any { it.isISOControl() && !(multiline && it in LINE_BREAKS) } -> {
                FieldIssue(field, "must not contain control characters").left()
            }

            else -> {
                value.right()
            }
        }
    }

    private fun tooShort(min: Int): String = if (min == 1) "must not be blank" else "must be at least $min characters"
}

/** An amount in minor units of one ISO-4217 [currency]; never negative (data-model section 2). */
data class Money(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(amountMinor >= 0) { "amountMinor must not be negative, was $amountMinor" }
        require(CURRENCY.matches(currency)) { "currency must be an ISO-4217 code, was $currency" }
    }

    companion object {
        private val CURRENCY = Regex("[A-Z]{3}")

        /** A product price: a positive amount of [currency] (data-model section 3.2, `price > 0`). */
        fun price(
            amountMinor: Long,
            currency: String,
        ): Either<FieldIssue, Money> =
            when {
                amountMinor <= 0 -> FieldIssue("price.amountMinor", "must be greater than 0").left()
                !CURRENCY.matches(currency) -> FieldIssue("price.currency", "must be an ISO-4217 code").left()
                else -> Money(amountMinor, currency).right()
            }
    }
}

/** Stock keeping unit: 3..32 characters of `A-Z`, `0-9` and `-`, unique within the catalogue. */
@JvmInline
value class Sku private constructor(
    val value: String,
) {
    companion object {
        private val PATTERN = Regex("[A-Z0-9-]{3,32}")
        private const val GENERATED_HEX = 10

        fun of(raw: String): Either<FieldIssue, Sku> =
            if (PATTERN.matches(raw)) {
                Sku(raw).right()
            } else {
                FieldIssue("sku", "must be 3 to 32 characters of A-Z, 0-9 and -").left()
            }

        /** The SKU given to a product created without one: `P-` and the first ten hex digits of its id. */
        fun generatedFor(productId: ProductId): Sku =
            Sku(
                "P-" +
                    productId.value
                        .toString()
                        .replace("-", "")
                        .take(GENERATED_HEX)
                        .uppercase(Locale.ROOT),
            )
    }
}

/** Name of a product: trimmed, 1..120 characters, no control characters. */
@JvmInline
value class ProductName private constructor(
    val value: String,
) {
    companion object {
        const val MAX: Int = 120

        fun of(raw: String): Either<FieldIssue, ProductName> = Text.bounded(raw, "name", 1..MAX).map(::ProductName)
    }
}

/** Name of a category: trimmed, 1..120 characters, no control characters. */
@JvmInline
value class CategoryName private constructor(
    val value: String,
) {
    companion object {
        const val MAX: Int = 120

        fun of(raw: String): Either<FieldIssue, CategoryName> = Text.bounded(raw, "name", 1..MAX).map(::CategoryName)
    }
}

/** A product or category description: trimmed, up to [max] characters; line breaks are allowed. */
@JvmInline
value class Description private constructor(
    val value: String,
) {
    companion object {
        const val PRODUCT_MAX: Int = 4000
        const val CATEGORY_MAX: Int = 1000

        /** The description of [raw]; null when [raw] is absent or blank. */
        fun of(
            raw: String?,
            max: Int = PRODUCT_MAX,
        ): Either<FieldIssue, Description?> =
            if (raw.isNullOrBlank()) {
                null.right()
            } else {
                Text.bounded(raw, "description", 1..max, multiline = true).map(::Description)
            }
    }
}

/** Where an image lives: an `https` URL or a storage key, at most 2048 characters. */
@JvmInline
value class ImageRef private constructor(
    val value: String,
) {
    companion object {
        const val MAX: Int = 2048
        const val ALT_TEXT_MAX: Int = 200
        private val HTTPS_URL = Regex("https://[^\\s/?#]+[^\\s]*")
        private val STORAGE_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")

        fun of(raw: String): Either<FieldIssue, ImageRef> {
            val value = raw.trim()
            return when {
                value.isEmpty() -> FieldIssue("url", "must not be blank").left()
                value.length > MAX -> FieldIssue("url", "must be at most $MAX characters").left()
                HTTPS_URL.matches(value) || STORAGE_KEY.matches(value) -> ImageRef(value).right()
                else -> FieldIssue("url", "must be an https URL or a storage key").left()
            }
        }

        /** The alternative text of an image: trimmed, up to 200 characters; null when absent or blank. */
        fun altText(raw: String?): Either<FieldIssue, String?> =
            if (raw.isNullOrBlank()) null.right() else Text.bounded(raw, "altText", 1..ALT_TEXT_MAX)
    }
}

/** A stock level: a number of units, never negative, at most [MAX]. */
@JvmInline
value class StockLevel private constructor(
    val value: Int,
) {
    companion object {
        const val MAX: Int = 1_000_000

        fun of(
            value: Int,
            field: String = "initialStock",
        ): Either<FieldIssue, StockLevel> =
            when {
                value < 0 -> FieldIssue(field, "must not be negative").left()
                value > MAX -> FieldIssue(field, "must be at most $MAX").left()
                else -> StockLevel(value).right()
            }
    }
}

/** Why stock was adjusted: trimmed, 3..200 characters, mandatory (FR-002). */
@JvmInline
value class StockAdjustmentReason private constructor(
    val value: String,
) {
    companion object {
        const val MIN: Int = 3
        const val MAX: Int = 200

        fun of(raw: String?): Either<FieldIssue, StockAdjustmentReason> =
            Text.bounded(raw.orEmpty(), "reason", MIN..MAX).map(::StockAdjustmentReason)
    }
}

/** Units of one product on a reservation line: 1..99 (data-model section 2). */
@JvmInline
value class Quantity private constructor(
    val value: Int,
) {
    companion object {
        const val MIN: Int = 1
        const val MAX: Int = 99

        fun of(value: Int): Either<FieldIssue, Quantity> =
            if (value in MIN..MAX) Quantity(value).right() else FieldIssue("quantity", "must be 1 to 99").left()
    }
}

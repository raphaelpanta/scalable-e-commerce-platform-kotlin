package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError

/** The client-chosen `Idempotency-Key` of a checkout or refund (FR-013): 1 to 128 printable ASCII, no whitespace. */
@JvmInline
value class IdempotencyKey private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 128
        private const val NOT_VISIBLE = "must be printable ASCII without whitespace"

        /** Validates [raw] as is (no trimming: the key is compared byte for byte). */
        fun of(
            raw: String,
            field: String = "Idempotency-Key",
        ): Validated<IdempotencyKey> =
            when {
                raw.isEmpty() -> ValidationError(field, "must not be blank").left()
                raw.length > MAX_LENGTH -> ValidationError(field, "must be at most $MAX_LENGTH characters").left()
                !TextRules.isVisibleAscii(raw) -> ValidationError(field, NOT_VISIBLE).left()
                else -> IdempotencyKey(raw).right()
            }
    }
}

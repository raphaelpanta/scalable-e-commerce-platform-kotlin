package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError
import java.util.UUID

/**
 * Helpers for the typed ids of every service (data-model section 1). Each service declares its own ids, for
 * example `@JvmInline value class OrderId(val value: UUID)` with
 * `fun of(raw: String) = Uuids.parse(raw, "orderId").map(::OrderId)`, so that ids of different concepts cannot be
 * mixed.
 */
object Uuids {
    /** The nil UUID, never a valid id. */
    val NIL: UUID = UUID(0L, 0L)

    private val CANONICAL = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** A new random (version 4) id. */
    fun random(): UUID = UUID.randomUUID()

    /** Parses the canonical 8-4-4-4-12 form, rejecting the nil UUID (`UUID.fromString` alone is more lenient). */
    fun parse(
        raw: String,
        field: String = "id",
    ): Validated<UUID> =
        when {
            !CANONICAL.matches(raw) -> ValidationError(field, "must be a UUID").left()
            else -> nonNil(UUID.fromString(raw), field)
        }

    /** Rejects the nil UUID. */
    fun nonNil(
        id: UUID,
        field: String = "id",
    ): Validated<UUID> = if (id == NIL) ValidationError(field, "must not be the nil UUID").left() else id.right()
}

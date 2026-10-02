package com.ecommerce.platform.core.result

import arrow.core.Either
import arrow.core.NonEmptyList

/**
 * One rejected input: the [field] it belongs to (the JSON member or query parameter name the client sent) and a
 * short, client-safe [reason] such as `must be at most 99`. Maps to one entry of a problem's `errors` array.
 */
data class ValidationError(
    val field: String,
    val reason: String,
)

/** The result of validating one value: the value or the first rule it breaks. */
typealias Validated<T> = Either<ValidationError, T>

/** The result of validating several values at once: the value or every rule that was broken. */
typealias ValidatedAll<T> = Either<NonEmptyList<ValidationError>, T>

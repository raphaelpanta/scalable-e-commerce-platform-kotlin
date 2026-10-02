package com.ecommerce.platform.core.result

import arrow.core.Either
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right

/** `Right(Unit)` when [condition] holds, otherwise `Left(ValidationError(field, reason()))`. */
fun ensureThat(
    condition: Boolean,
    field: String,
    reason: () -> String,
): Validated<Unit> = if (condition) Unit.right() else ValidationError(field, reason()).left()

/** Lifts a single-error result into the accumulating shape used when several values are validated together. */
fun <T> Validated<T>.accumulating(): ValidatedAll<T> = mapLeft { nonEmptyListOf(it) }

/** Validates two values independently and combines them, reporting every broken rule (not only the first). */
fun <A, B, R> validateAll(
    a: Validated<A>,
    b: Validated<B>,
    combine: (A, B) -> R,
): ValidatedAll<R> = Either.zipOrAccumulate(a, b, combine)

/** Three-value form of [validateAll]. */
fun <A, B, C, R> validateAll(
    a: Validated<A>,
    b: Validated<B>,
    c: Validated<C>,
    combine: (A, B, C) -> R,
): ValidatedAll<R> = Either.zipOrAccumulate(a, b, c, combine)

/** Four-value form of [validateAll]. */
fun <A, B, C, D, R> validateAll(
    a: Validated<A>,
    b: Validated<B>,
    c: Validated<C>,
    d: Validated<D>,
    combine: (A, B, C, D) -> R,
): ValidatedAll<R> = Either.zipOrAccumulate(a, b, c, d, combine)

/** Five-value form of [validateAll]. */
@Suppress("LongParameterList") // mirrors Arrow's zipOrAccumulate arities
fun <A, B, C, D, E, R> validateAll(
    a: Validated<A>,
    b: Validated<B>,
    c: Validated<C>,
    d: Validated<D>,
    e: Validated<E>,
    combine: (A, B, C, D, E) -> R,
): ValidatedAll<R> = Either.zipOrAccumulate(a, b, c, d, e, combine)

/** Six-value form of [validateAll]. */
@Suppress("LongParameterList") // mirrors Arrow's zipOrAccumulate arities
fun <A, B, C, D, E, F, R> validateAll(
    a: Validated<A>,
    b: Validated<B>,
    c: Validated<C>,
    d: Validated<D>,
    e: Validated<E>,
    f: Validated<F>,
    combine: (A, B, C, D, E, F) -> R,
): ValidatedAll<R> = Either.zipOrAccumulate(a, b, c, d, e, f, combine)

/** Seven-value form of [validateAll]. */
@Suppress("LongParameterList") // mirrors Arrow's zipOrAccumulate arities
fun <A, B, C, D, E, F, G, R> validateAll(
    a: Validated<A>,
    b: Validated<B>,
    c: Validated<C>,
    d: Validated<D>,
    e: Validated<E>,
    f: Validated<F>,
    g: Validated<G>,
    combine: (A, B, C, D, E, F, G) -> R,
): ValidatedAll<R> = Either.zipOrAccumulate(a, b, c, d, e, f, g, combine)

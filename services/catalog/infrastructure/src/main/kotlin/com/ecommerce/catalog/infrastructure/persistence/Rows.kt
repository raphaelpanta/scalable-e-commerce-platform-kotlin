package com.ecommerce.catalog.infrastructure.persistence

import arrow.core.Either
import arrow.core.getOrElse
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import java.util.Locale

/** The non-null value of column [name]. */
internal inline fun <reified T : Any> Readable.required(name: String): T =
    checkNotNull(get(name, T::class.javaObjectType)) { "column $name is null" }

/** The value of column [name], or null. */
internal inline fun <reified T : Any> Readable.optional(name: String): T? = get(name, T::class.javaObjectType)

/** Binds [value], or a typed null. */
internal fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

/** Binds every entry of [bindings]. */
internal fun DatabaseClient.GenericExecuteSpec.bindAll(bindings: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
    bindings.entries.fold(this) { spec, (name, value) -> spec.bind(name, value) }

/** A stored value the domain validated when it was written; a broken one is a data error and fails fast. */
internal fun <T> Either<*, T>.trusted(column: String): T = getOrElse { error("stored $column is invalid: $it") }

/** The column value of an enum: its name in lower case. */
internal fun Enum<*>.column(): String = name.lowercase(Locale.ROOT)

/** The enum constant of [T] stored as [value]. */
internal inline fun <reified T : Enum<T>> enumOf(value: String): T = enumValueOf(value.uppercase(Locale.ROOT))

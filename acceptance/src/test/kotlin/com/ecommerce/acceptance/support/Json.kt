package com.ecommerce.acceptance.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.MissingNode
import tools.jackson.module.kotlin.KotlinModule

/** JSON for every client of the suite: Jackson 3 with the Kotlin module. Responses are read as trees. */
object Json {
    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()

    fun write(value: Any): String = mapper.writeValueAsString(value)

    /** Parses [text]; an empty or non-JSON body becomes a missing node so that field lookups simply find nothing. */
    fun read(text: String): JsonNode =
        if (text.isBlank()) {
            MissingNode.getInstance()
        } else {
            runCatching { mapper.readTree(text) }.getOrElse { MissingNode.getInstance() }
        }
}

/** The text value of [field], or null when absent or not a string. */
fun JsonNode.string(field: String): String? = path(field).takeIf { it.isString }?.stringValue()

/** The text value of [field], failing the step when it is absent. */
fun JsonNode.requireString(field: String): String = checkNotNull(string(field)) { "Field '$field' missing in $this" }

/** Elements of the array [field] (empty when absent). */
fun JsonNode.list(field: String): List<JsonNode> = path(field).values().toList()

/** The `items` of a page response (`{items, page, size, totalItems}`). */
fun JsonNode.items(): List<JsonNode> = list("items")

/** Minor units of the money object [field] (`{amountMinor, currency}`). */
fun JsonNode.minor(field: String): Long = path(field).path("amountMinor").asLong()

/** A money object of the contracts for an amount in minor units. */
fun money(amountMinor: Long): Map<String, Any> = mapOf("amountMinor" to amountMinor, "currency" to Environment.currency)

/** Minor units as a decimal amount with two digits, as written in the feature files (`25.00`). */
fun Long.asAmount(): String = "${this / CENTS}.${(this % CENTS).toString().padStart(2, '0')}"

private const val CENTS = 100L

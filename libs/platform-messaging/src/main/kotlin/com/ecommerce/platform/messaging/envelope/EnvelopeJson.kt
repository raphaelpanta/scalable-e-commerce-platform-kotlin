package com.ecommerce.platform.messaging.envelope

import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jacksonTypeRef

/**
 * The JSON form of envelopes on Kafka (UTF-8): ISO-8601 UTC instants with `Z`, UUIDs as strings, unknown fields
 * ignored on read so that additive v1 changes never break a consumer. The payload of a read envelope stays a
 * [tools.jackson.databind.JsonNode] until [convert] (or [payloadAs]) turns it into the consumer's own type.
 */
object EnvelopeJson {
    /** The mapper behind every envelope conversion; services may reuse it for payload snapshots. */
    val mapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .build()

    private val RECEIVED: TypeReference<ReceivedEnvelope> = jacksonTypeRef()

    /** The compact JSON of [envelope]. */
    fun write(envelope: Envelope<*>): String = mapper.writeValueAsString(envelope)

    /** Reads an envelope; throws [MalformedEnvelopeException] when [json] is not a valid envelope. */
    fun read(json: String): ReceivedEnvelope {
        // Kotlin's constructor checks (Envelope.init) surface as Jackson value-instantiation exceptions.
        val envelope: ReceivedEnvelope? =
            try {
                mapper.readValue(json, RECEIVED)
            } catch (malformed: JacksonException) {
                throw MalformedEnvelopeException("not a valid event envelope: ${malformed.originalMessage}", malformed)
            }
        return envelope ?: throw MalformedEnvelopeException("not a valid event envelope: null")
    }

    /** Converts a payload (a JSON tree or any object) to [type]. */
    fun <T : Any> convert(
        payload: Any,
        type: TypeReference<T>,
    ): T = mapper.convertValue(payload, type)
}

/** A record whose value is not an envelope; consumers send it to the dead-letter topic without retrying. */
class MalformedEnvelopeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

package com.ecommerce.order.infrastructure

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.DslPart
import au.com.dius.pact.consumer.dsl.LambdaDsl
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslRequestWithPath
import au.com.dius.pact.consumer.dsl.PactDslResponse
import au.com.dius.pact.consumer.dsl.PactDslWithState
import au.com.dius.pact.core.model.V4Pact
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.http.WebClientDefaults
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.ReceivedEnvelope
import com.ecommerce.platform.testing.InternalToken
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import org.springframework.web.reactive.function.client.WebClient
import reactor.util.context.Context

/** Fixed values of pact-interactions.md section 1 shared by the consumer pacts of order. */
object PactValues {
    const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
    const val CORRELATION_REGEX = "^[A-Za-z0-9-]{1,64}$"
    const val TIMESTAMP_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"
    const val JSON = "application/json"
    const val JSON_REGEX = "^application/json(;.*)?$"
    const val PROBLEM_JSON = "application/problem+json"
    const val PROBLEM_REGEX = "^application/problem\\+json(;.*)?$"
    const val PROBLEMS = "https://ecommerce.example/problems/"
    const val ADA = "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"
    const val ORDER_1 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"
    const val ORDER_2 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11"
    const val ORDER_3 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"
    const val ORDER_4 = "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a13"
    const val ESPRESSO = "9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01"
    const val BEANS = "3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02"
    const val KETTLE = "a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    const val UNKNOWN_PRODUCT = "d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"
    const val CART = "8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34"
    const val LINE_1 = "c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18"
    const val LINE_2 = "e2b0f3a5-7c18-4e4b-9f62-1c8e5b0d3a29"
    const val RESERVATION = "4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15"
    const val ATTEMPT_APPROVED = "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"
    const val ATTEMPT_PENDING = "d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60"
    const val ATTEMPT_DECLINED = "8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e"
    const val KEY_1 = "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    const val KEY_2 = "3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92"
    const val KEY_3 = "2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81"
    const val KEY_REFUND = "9c8b7a69-5847-4362-9d1e-0f1a2b3c4d5e"
    const val REFUND = "4d5e6f70-8192-4a3b-8c4d-5e6f70819203"
    const val ADDRESS_OWNED = "5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11"
    const val ADDRESS_FOREIGN = "6a1d2b63-4b54-4b64-8a69-8b2e9c0e3d22"
    const val ADDRESS_UNKNOWN = "7b2e3c74-5c65-4c75-9b7a-9c3f0d1f4e33"
    const val OTHER_ACCOUNT = "9e8d7c6b-5a49-4382-9f1e-0d2c4b6a8e10"
    const val OK = 200
    const val CREATED = 201
    const val NO_CONTENT = 204
    const val NOT_FOUND = 404
    const val CONFLICT = 409
}

/** The common request headers of an internal call (`Accept`, `X-Internal-Token`, `X-Correlation-Id`). */
fun PactDslWithState.internalRequest(
    description: String,
    method: String,
    path: String,
): PactDslRequestWithPath =
    uponReceiving(description)
        .path(path)
        .method(method)
        .headers(mapOf("Accept" to PactValues.JSON, InternalToken.HEADER to InternalToken.TEST))
        .matchHeader(CorrelationIds.HEADER, PactValues.CORRELATION_REGEX, PactValues.CORRELATION_ID)

/** A JSON body built with the lambda DSL. */
fun json(build: (LambdaDslObject) -> Unit): DslPart = LambdaDsl.newJsonBody { build(it) }.build()

/** A JSON body sent with the request. */
fun PactDslRequestWithPath.jsonBody(body: DslPart): PactDslRequestWithPath =
    headers("Content-Type", PactValues.JSON).body(body)

/** A JSON answer with the echoed correlation id. */
fun PactDslRequestWithPath.jsonAnswer(
    status: Int,
    body: DslPart,
): PactDslResponse =
    willRespondWith()
        .status(status)
        .matchHeader("Content-Type", PactValues.JSON_REGEX, PactValues.JSON)
        .matchHeader(CorrelationIds.HEADER, PactValues.CORRELATION_REGEX, PactValues.CORRELATION_ID)
        .body(body)

/** A 204 answer (no body, no content type). */
fun PactDslRequestWithPath.noContent(): PactDslResponse =
    willRespondWith()
        .status(PactValues.NO_CONTENT)
        .matchHeader(CorrelationIds.HEADER, PactValues.CORRELATION_REGEX, PactValues.CORRELATION_ID)

/** An RFC 9457 answer: `type`, `title`, `status` exact, `detail` and `correlationId` as strings. */
fun PactDslRequestWithPath.problemAnswer(
    status: Int,
    slug: String,
    title: String,
    detail: String,
    extra: (LambdaDslObject) -> Unit = {},
): PactDslResponse =
    willRespondWith()
        .status(status)
        .matchHeader("Content-Type", PactValues.PROBLEM_REGEX, PactValues.PROBLEM_JSON)
        .matchHeader(CorrelationIds.HEADER, PactValues.CORRELATION_REGEX, PactValues.CORRELATION_ID)
        .body(
            json { body ->
                body.stringValue("type", PactValues.PROBLEMS + slug)
                body.stringValue("title", title)
                body.numberValue("status", status)
                body.stringType("detail", detail)
                body.stringType("correlationId", PactValues.CORRELATION_ID)
                extra(body)
            },
        )

/** `{"amountMinor": .., "currency": "BRL"}` under [name], exact. */
fun LambdaDslObject.money(
    name: String,
    amountMinor: Long,
) {
    `object`(name) {
        it.numberValue("amountMinor", amountMinor)
        it.stringValue("currency", "BRL")
    }
}

/** The envelope members of an event: `eventId` uuid, timestamps, correlation id by regex, the rest exact. */
@Suppress("LongParameterList") // the envelope members of events.yaml
fun envelope(
    eventId: String,
    type: String,
    occurredAt: String,
    aggregateId: String,
    producer: String,
    payload: (LambdaDslObject) -> Unit,
): DslPart =
    json { body ->
        body.uuid("eventId", java.util.UUID.fromString(eventId))
        body.stringValue("type", type)
        body.numberValue("version", 1)
        body.stringMatcher("occurredAt", PactValues.TIMESTAMP_REGEX, occurredAt)
        body.stringValue("aggregateId", aggregateId)
        body.stringMatcher("correlationId", PactValues.CORRELATION_REGEX, PactValues.CORRELATION_ID)
        body.stringValue("producer", producer)
        body.`object`("payload") { payload(it) }
    }

/** The single asynchronous message of [pact] as the consumer receives it. */
fun receivedMessage(pact: V4Pact): ReceivedEnvelope {
    val message = checkNotNull(pact.interactions.single().asAsynchronousMessage())
    return EnvelopeJson.read(checkNotNull(message.contents.contents.valueAsString()))
}

/** An internal client of the mock server, configured like the service's own. */
fun internalClient(mockServer: MockServer): WebClient =
    WebClientDefaults.internalClient(WebClient.builder(), mockServer.getUrl(), InternalToken.TEST)

/** Runs an adapter call with the correlation id of the pacts in its Reactor context. */
fun <T> withPactCorrelation(block: suspend () -> T): T =
    runBlocking(ReactorContext(Context.of(CorrelationIds.CONTEXT_KEY, PactValues.CORRELATION_ID))) { block() }

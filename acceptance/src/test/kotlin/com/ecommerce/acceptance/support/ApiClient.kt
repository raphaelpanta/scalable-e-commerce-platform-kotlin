package com.ecommerce.acceptance.support

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.util.UUID

/**
 * Talks to the platform through the gateway only. Every request carries the scenario's `X-Correlation-Id` unless
 * the caller passes another one in [send]'s headers; the last response is kept for the `Then` steps.
 */
class ApiClient(
    val correlationId: String,
) {
    @Volatile
    private var latest: ApiResponse? = null

    /** The response to the most recent request of the scenario. */
    val last: ApiResponse
        get() = checkNotNull(latest) { "No request has been sent in this scenario yet" }

    fun get(
        path: String,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = send("GET", path, null, bearer, headers)

    fun post(
        path: String,
        body: Any? = null,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = send("POST", path, body, bearer, headers)

    fun put(
        path: String,
        body: Any,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = send("PUT", path, body, bearer, headers)

    fun delete(
        path: String,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = send("DELETE", path, null, bearer, headers)

    fun send(
        method: String,
        path: String,
        body: Any?,
        bearer: String?,
        headers: Map<String, String>,
    ): ApiResponse {
        val builder = HttpRequest.newBuilder(URI.create(Environment.gatewayUrl + path)).timeout(Http.requestTimeout)
        builder.setHeader("Accept", "application/json, application/problem+json")
        builder.setHeader(Headers.CORRELATION_ID, correlationId)
        bearer?.let { builder.setHeader(Headers.AUTHORIZATION, "Bearer $it") }
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                builder.setHeader("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(Json.write(body))
            }
        val response = Http.send(builder.method(method, publisher).build())
        return ApiResponse(response.statusCode(), response.headers(), response.body()).also { latest = it }
    }

    companion object {
        fun newKey(): String = UUID.randomUUID().toString()

        /** Headers carrying a fresh correlation id, to single out one request in the central log. */
        fun withCorrelationId(id: String): Map<String, String> = mapOf(Headers.CORRELATION_ID to id)
    }
}

/** One answer of the platform. The body is parsed lazily into a tree; [raw] is never printed unredacted. */
class ApiResponse(
    val status: Int,
    private val headers: HttpHeaders,
    private val raw: String,
) {
    val body: JsonNode by lazy { Json.read(raw) }

    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    /** The slug of the RFC 9457 problem `type` (`https://ecommerce.example/problems/<slug>`), if any. */
    fun problemType(): String? = body.string("type")?.substringAfterLast("/problems/")

    /** A one-line description for assertion messages, with credentials and tokens masked. */
    fun describe(): String = "HTTP $status ${SECRETS.replace(raw, "\"$1\":\"***\"").take(MAX_DESCRIBED)}"

    override fun toString(): String = describe()

    private companion object {
        val SECRETS = Regex(""""(accessToken|refreshToken|password|newPassword|token|code)"\s*:\s*"[^"]*"""")
        const val MAX_DESCRIBED = 600
    }
}

/** Asserts the status code, printing the (redacted) response when it differs. */
infix fun ApiResponse.shouldHaveStatus(expected: Int) {
    withClue(describe()) { status shouldBe expected }
}

/** Asserts an RFC 9457 problem with the given status and `type` slug. */
fun ApiResponse.shouldBeProblem(
    expectedStatus: Int,
    slug: String,
) {
    this shouldHaveStatus expectedStatus
    withClue(describe()) { problemType() shouldBe slug }
}

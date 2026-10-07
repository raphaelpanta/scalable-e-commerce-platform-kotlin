package com.ecommerce.acceptance.support

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * What a shopper's browser does for one page view of the storefront (feature 005, FR-031, FR-032): the requests it
 * sends with the page view's correlation id, `X-Browser-Session: cookie` and a `traceparent` it minted, and the
 * OTLP/HTTP JSON it reports to the gateway's telemetry routes (`telemetry.yaml`). The reported attributes are the
 * allow-list of data-model.md section 3.5, under the route templates the storefront reduces its URLs to.
 */
class PageView(
    val correlationId: String = ApiClient.newKey(),
    private val sessionId: String = ApiClient.newKey(),
) {
    /** The trace of the page view; the storefront's span is the parent of every platform span of its request. */
    val traceId: String = hex(TRACE_ID_LENGTH)
    private val spanId: String = hex(SPAN_ID_LENGTH)

    /** The headers the storefront's fetch adds to a platform request. */
    fun requestHeaders(): Map<String, String> =
        mapOf(
            Headers.CORRELATION_ID to correlationId,
            Headers.BROWSER_SESSION to "cookie",
            Headers.TRACEPARENT to "00-$traceId-$spanId-01",
        )

    /** An `ExportTraceServiceRequest` with the span of the platform call [route] made by this page view. */
    fun traces(
        route: String,
        disclosure: Disclosure? = null,
    ): Map<String, Any> {
        val now = Instant.now()
        val attributes =
            listOf(
                text("http.route", route),
                text("http.request.method", "GET"),
                number("http.response.status_code", HTTP_OK),
                number("duration.ms", DURATION_MILLIS),
                text("correlation.id", correlationId),
            ) + disclosure?.let(::unfilteredAttributes).orEmpty()
        val span =
            mapOf(
                "traceId" to traceId,
                "spanId" to spanId,
                "name" to "GET $route",
                "kind" to SPAN_KIND_CLIENT,
                "startTimeUnixNano" to nanos(now.minusMillis(DURATION_MILLIS)),
                "endTimeUnixNano" to nanos(now),
                "attributes" to attributes,
            )
        return mapOf(
            "resourceSpans" to
                listOf(
                    mapOf(
                        "resource" to resource(),
                        "scopeSpans" to
                            listOf(
                                mapOf("scope" to mapOf("name" to "storefront"), "spans" to listOf(span)),
                            ),
                    ),
                ),
        )
    }

    /** An `ExportLogsServiceRequest` with the `client.error` record of a failed action of this page view. */
    fun logs(
        route: String,
        disclosure: Disclosure? = null,
    ): Map<String, Any> {
        val attributes =
            listOf(
                text("error.name", "ProblemError"),
                text("http.route", route),
                number("http.response.status_code", HTTP_CONFLICT),
                text("correlation.id", correlationId),
            ) + disclosure?.let(::unfilteredAttributes).orEmpty()
        val record =
            mapOf(
                "timeUnixNano" to nanos(Instant.now()),
                "severityNumber" to SEVERITY_ERROR,
                "severityText" to "ERROR",
                "body" to mapOf("stringValue" to "client.error"),
                "attributes" to attributes,
                "traceId" to traceId,
                "spanId" to spanId,
            )
        return mapOf(
            "resourceLogs" to
                listOf(
                    mapOf(
                        "resource" to resource(),
                        "scopeLogs" to
                            listOf(mapOf("scope" to mapOf("name" to "storefront"), "logRecords" to listOf(record))),
                    ),
                ),
        )
    }

    private fun resource(): Map<String, Any> =
        mapOf(
            "attributes" to
                listOf(
                    text("service.name", "storefront"),
                    text("service.version", "0.1.0"),
                    text("session.id", sessionId),
                ),
        )

    /**
     * What a page that does not filter its telemetry would attach: the raw URL with its query, the shopper's
     * identity, the typed address and the search term, and the email in an attribute the allow-list keeps. The
     * collector must keep all of it out of Loki and Tempo.
     */
    private fun unfilteredAttributes(disclosure: Disclosure): List<Map<String, Any>> =
        listOf(
            text("http.url", "http://localhost/search?q=${disclosure.searchTerm}&email=${disclosure.email}"),
            text("url.full", "http://localhost/search?q=${disclosure.searchTerm}"),
            text("http.target", "/search?q=${disclosure.searchTerm}"),
            text("user.email", disclosure.email),
            text("enduser.id", disclosure.email),
            text("form.value", disclosure.address),
            text("search.term", disclosure.searchTerm),
            text("exception.message", disclosure.address),
            text("error.type", disclosure.email),
        )

    private companion object {
        const val TRACE_ID_LENGTH = 32
        const val SPAN_ID_LENGTH = 16
        const val SPAN_KIND_CLIENT = 3
        const val SEVERITY_ERROR = 17
        const val HTTP_OK = 200L
        const val HTTP_CONFLICT = 409L
        const val DURATION_MILLIS = 120L

        fun hex(length: Int): String =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(length)

        fun text(
            key: String,
            value: String,
        ): Map<String, Any> = mapOf("key" to key, "value" to mapOf("stringValue" to value))

        fun number(
            key: String,
            value: Long,
        ): Map<String, Any> = mapOf("key" to key, "value" to mapOf("intValue" to value.toString()))
    }
}

/** What the scenario's shopper typed or saved, which no storefront telemetry may contain (SC-011). */
data class Disclosure(
    val email: String,
    val address: String,
    val searchTerm: String,
) {
    /** The texts to look for: as written, URL-encoded (`+` and `%20`), in any letter case. */
    fun needles(): List<String> =
        listOf(email, address, searchTerm).flatMap { text ->
            val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8)
            listOf(text, encoded, encoded.replace("+", "%20"))
        }
}

/** The Unix time of [instant] in nanoseconds, as OTLP/JSON writes it (a string). */
fun nanos(instant: Instant): String {
    val fraction = instant.nano.toString().padStart(NANO_DIGITS, '0')
    return "${instant.epochSecond}$fraction"
}

private const val NANO_DIGITS = 9

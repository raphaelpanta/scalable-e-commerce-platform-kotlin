package com.ecommerce.acceptance.support

import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Reads the central log (Loki) and the metrics (Prometheus) of the observability profile. Neither is published to
 * the host, so both are queried through Grafana's data source proxy (data source uids `loki` and `prometheus`
 * from platform/observability/grafana/provisioning); `LOKI_VIA_GRAFANA=false` queries `LOKI_URL` directly.
 */
class Telemetry {
    /** The `service` labels of the log entries carrying [correlationId] in the last [lookBack]. */
    fun servicesLogging(
        correlationId: String,
        lookBack: Duration = Duration.ofMinutes(LOOK_BACK_MINUTES),
    ): Set<String> {
        val now = Instant.now()
        val query = """{service=~".+"} | json | correlationId="$correlationId""""
        val path =
            "/loki/api/v1/query_range?query=${encode(query)}&limit=$LOG_LIMIT&direction=backward" +
                "&start=${nanos(now.minus(lookBack))}&end=${nanos(now.plusSeconds(1))}"
        val result = lokiGet(path).path("data").list("result")
        return result
            .map { it.path("stream") }
            .mapNotNull { it.string("service") ?: it.string("service_name") }
            .toSet()
    }

    /** The storefront's browser records in the central log under [correlationId] (attribute `correlation.id`). */
    fun storefrontLogs(
        correlationId: String,
        lookBack: Duration = Duration.ofMinutes(LOOK_BACK_MINUTES),
    ): List<LogEntry> = logEntries("""{service="storefront"} | correlation_id="$correlationId"""", lookBack)

    /** Every entry of the storefront in the central log of the last [lookBack], with its labels and metadata. */
    fun allStorefrontLogs(lookBack: Duration = Duration.ofMinutes(LOOK_BACK_MINUTES)): List<LogEntry> =
        logEntries("""{service="storefront"}""", lookBack)

    /** The services whose spans belong to the trace [traceId] (Tempo); empty while the trace is not stored yet. */
    fun traceServices(traceId: String): Set<String> =
        traceDocument(traceId)?.let { resourceSpansOf(it).mapNotNull(::serviceOf).toSet() }.orEmpty()

    /**
     * The JSON of every span batch that belongs to the storefront (`service.name=storefront`) in the traces of the
     * last [lookBack]: what Tempo stores of the browser, without the spans the platform's services added.
     */
    fun allStorefrontSpans(lookBack: Duration = Duration.ofMinutes(LOOK_BACK_MINUTES)): List<String> {
        val now = Instant.now()
        val query = """{resource.service.name="storefront"}"""
        val path =
            "/api/search?q=${encode(query)}&limit=$TRACE_LIMIT" +
                "&start=${now.minus(lookBack).epochSecond}&end=${now.plusSeconds(1).epochSecond}"
        val traces = grafanaGet("/api/datasources/proxy/uid/tempo$path").list("traces")
        return traces
            .mapNotNull { it.string("traceID") }
            .mapNotNull(::traceDocument)
            .flatMap(::resourceSpansOf)
            .filter { serviceOf(it) == "storefront" }
            .map(JsonNode::toString)
    }

    private fun logEntries(
        query: String,
        lookBack: Duration,
    ): List<LogEntry> {
        val now = Instant.now()
        val path =
            "/loki/api/v1/query_range?query=${encode(query)}&limit=$LOG_LIMIT&direction=backward" +
                "&start=${nanos(now.minus(lookBack))}&end=${nanos(now.plusSeconds(1))}"
        return lokiGet(path).path("data").list("result").flatMap { result ->
            val labels = result.path("stream").properties().associate { (name, value) -> name to value.asString() }
            result.list("values").map { LogEntry(labels, it.path(1).asString()) }
        }
    }

    private fun traceDocument(traceId: String): JsonNode? {
        val response =
            Http.get(
                URI.create("${Environment.grafanaUrl}/api/datasources/proxy/uid/tempo/api/traces/$traceId"),
                grafanaHeaders() + mapOf("Accept" to "application/json"),
            )
        return if (response.statusCode() == Status.NOT_FOUND) null else read(response.statusCode() to response.body())
    }

    /** The `ResourceSpans` of a Tempo trace, whichever of its JSON layouts (`batches`, `resourceSpans`) it uses. */
    private fun resourceSpansOf(document: JsonNode): List<JsonNode> =
        listOf(document.list("batches"), document.list("resourceSpans"), document.path("trace").list("resourceSpans"))
            .flatten()

    private fun serviceOf(resourceSpans: JsonNode): String? =
        resourceSpans
            .path("resource")
            .list("attributes")
            .firstOrNull { it.string("key") == "service.name" }
            ?.path("value")
            ?.string("stringValue")

    /** The instant vector of a PromQL [query]. */
    fun prometheus(query: String): List<JsonNode> =
        grafanaGet("/api/datasources/proxy/uid/prometheus/api/v1/query?query=${encode(query)}")
            .path("data")
            .list("result")

    private fun lokiGet(path: String): JsonNode =
        if (Environment.lokiViaGrafana) {
            grafanaGet("/api/datasources/proxy/uid/loki$path")
        } else {
            read(Http.get(URI.create(Environment.lokiUrl + path)).let { it.statusCode() to it.body() })
        }

    private fun grafanaGet(path: String): JsonNode {
        val response = Http.get(URI.create(Environment.grafanaUrl + path), grafanaHeaders())
        return read(response.statusCode() to response.body())
    }

    private fun grafanaHeaders(): Map<String, String> {
        val credentials = "${Environment.grafanaUser}:${Environment.grafanaPassword}"
        val authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
        return mapOf(Headers.AUTHORIZATION to authorization)
    }

    private fun read(answer: Pair<Int, String>): JsonNode {
        check(answer.first == Status.OK) { "Telemetry query failed: HTTP ${answer.first}" }
        return Json.read(answer.second)
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val LOOK_BACK_MINUTES = 15L
        const val LOG_LIMIT = 1000
        const val TRACE_LIMIT = 50
    }
}

/** One entry of the central log: its stream labels (including the structured metadata Loki merges in) and its line. */
data class LogEntry(
    val labels: Map<String, String>,
    val line: String,
) {
    /** Everything the entry says, for a search of a text in it. */
    fun text(): String = (labels.entries.map { "${it.key}=${it.value}" } + line).joinToString("\n")
}

/** The deployable units of the platform, as labelled `service` in logs and metrics. */
val PLATFORM_SERVICES: List<String> =
    listOf("gateway", "identity", "catalog", "cart", "order", "payment", "notification")

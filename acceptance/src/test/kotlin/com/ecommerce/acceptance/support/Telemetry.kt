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
        val credentials = "${Environment.grafanaUser}:${Environment.grafanaPassword}"
        val authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
        val headers = mapOf(Headers.AUTHORIZATION to authorization)
        val response = Http.get(URI.create(Environment.grafanaUrl + path), headers)
        return read(response.statusCode() to response.body())
    }

    private fun read(answer: Pair<Int, String>): JsonNode {
        check(answer.first == Status.OK) { "Telemetry query failed: HTTP ${answer.first}" }
        return Json.read(answer.second)
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun nanos(instant: Instant): String {
        val fraction = instant.nano.toString().padStart(NANO_DIGITS, '0')
        return "${instant.epochSecond}$fraction"
    }

    private companion object {
        const val LOOK_BACK_MINUTES = 15L
        const val LOG_LIMIT = 1000
        const val NANO_DIGITS = 9
    }
}

/** The deployable units of the platform, as labelled `service` in logs and metrics. */
val PLATFORM_SERVICES: List<String> =
    listOf("gateway", "identity", "catalog", "cart", "order", "payment", "notification")

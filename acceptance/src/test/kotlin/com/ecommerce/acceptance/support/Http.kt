package com.ecommerce.acceptance.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The one `java.net.http` client of the suite, shared by the gateway, Mailpit and Grafana clients. */
internal object Http {
    private val connectTimeout: Duration = Duration.ofSeconds(10)
    val requestTimeout: Duration = Duration.ofSeconds(30)

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    fun send(request: HttpRequest): HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())

    /** A GET on [uri] with optional extra headers (for example Grafana's basic authentication). */
    fun get(
        uri: URI,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        return send(builder.GET().build())
    }

    /** A PUT of a JSON document on [uri]. */
    fun putJson(
        uri: URI,
        body: Any,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
        builder.setHeader("Content-Type", "application/json")
        return send(builder.PUT(HttpRequest.BodyPublishers.ofString(Json.write(body))).build())
    }
}

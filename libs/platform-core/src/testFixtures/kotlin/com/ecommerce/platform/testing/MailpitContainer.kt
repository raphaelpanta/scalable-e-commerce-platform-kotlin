package com.ecommerce.platform.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Mailpit ([Images.MAILPIT]): SMTP on [SMTP_PORT] (point `SMTP_HOST`/`SMTP_PORT` at [smtpHost]/[smtpPort]) and a
 * small reader of its REST API on [HTTP_PORT] to assert on delivered messages.
 */
class MailpitContainer : GenericContainer<MailpitContainer>(DockerImageName.parse(Images.MAILPIT)) {
    init {
        withExposedPorts(SMTP_PORT, HTTP_PORT)
        waitingFor(Wait.forHttp("/api/v1/messages").forPort(HTTP_PORT).forStatusCode(OK))
    }

    /** One delivered message as listed by Mailpit. */
    data class Message(
        val id: String,
        val from: String,
        val to: List<String>,
        val subject: String,
        val snippet: String,
    )

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val json: JsonMapper = JsonMapper.builder().build()

    /** Host of the SMTP listener. */
    val smtpHost: String get() = host

    /** Mapped SMTP port. */
    val smtpPort: Int get() = getMappedPort(SMTP_PORT)

    /** Base URL of the web UI and REST API. */
    val apiUrl: String get() = "http://$host:${getMappedPort(HTTP_PORT)}"

    /** Every message currently held, newest first. */
    fun messages(): List<Message> {
        val body = json.readValue(get("/api/v1/messages"), Map::class.java)
        val messages = (body["messages"] as? List<*>).orEmpty()
        return messages.filterIsInstance<Map<*, *>>().map { message ->
            Message(
                id = message["ID"].toString(),
                from = address(message["From"]),
                to = (message["To"] as? List<*>).orEmpty().map(::address),
                subject = message["Subject"]?.toString().orEmpty(),
                snippet = message["Snippet"]?.toString().orEmpty(),
            )
        }
    }

    /** The plain-text body of the message with [id]. */
    fun text(id: String): String =
        json.readValue(get("/api/v1/message/$id"), Map::class.java)["Text"]?.toString().orEmpty()

    /** Deletes every message (call between tests). */
    fun deleteAll() {
        val request = HttpRequest.newBuilder(URI.create("$apiUrl/api/v1/messages")).DELETE().build()
        http.send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun get(path: String): String =
        http
            .send(
                HttpRequest.newBuilder(URI.create(apiUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    private fun address(value: Any?): String = (value as? Map<*, *>)?.get("Address")?.toString().orEmpty()

    companion object {
        const val SMTP_PORT: Int = 1025
        const val HTTP_PORT: Int = 8025
        private const val OK = 200
    }
}

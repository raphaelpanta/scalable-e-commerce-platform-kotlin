package com.ecommerce.acceptance.support

import org.awaitility.Awaitility
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A message caught by Mailpit: its id, subject and plain-text body (HTML stripped of tags when there is no text). */
class MailMessage(
    val id: String,
    val subject: String,
    val text: String,
)

/**
 * Reads the email sink of the Compose stack through Mailpit's API (`/api/v1/search`, `/api/v1/message/{id}`) and
 * drives its chaos triggers to make the email channel fail. Tokens are extracted from bodies, never printed.
 */
class MailpitClient(
    private val baseUrl: String = Environment.mailpitUrl,
) {
    /** Summaries of every message addressed to [address], newest first. */
    fun messagesTo(address: String): List<JsonNode> = search("to:\"$address\"")

    /** Ids of the messages already addressed to [address], to tell later messages apart. */
    fun idsTo(address: String): Set<String> = messagesTo(address).mapNotNull { it.string("ID") }.toSet()

    /** The full message [id]. */
    fun message(id: String): MailMessage {
        val response = Http.get(URI.create("$baseUrl/api/v1/message/$id"))
        check(response.statusCode() == Status.OK) { "Mailpit could not read a message: HTTP ${response.statusCode()}" }
        val node = Json.read(response.body())
        val text = node.string("Text")?.takeIf { it.isNotBlank() } ?: node.string("HTML").orEmpty().replace(TAG, " ")
        return MailMessage(id, node.string("Subject").orEmpty(), text)
    }

    /** Waits for a message to [address] that is not in [known] and satisfies [matching]. */
    fun awaitMessageTo(
        address: String,
        known: Set<String> = emptySet(),
        within: Duration = Budgets.notification,
        matching: (MailMessage) -> Boolean = { true },
    ): MailMessage {
        var found: MailMessage? = null
        Awaitility
            .await("a message to the shopper")
            .atMost(within)
            .pollInterval(Duration.ofSeconds(1))
            .until {
                found = newMessages(address, known).firstOrNull(matching)
                found != null
            }
        return checkNotNull(found)
    }

    /** Messages to [address] whose body mentions [text]. */
    fun messagesMentioning(
        address: String,
        text: String,
    ): List<MailMessage> =
        newMessages(address, emptySet()).filter { message ->
            message.text.contains(text) || message.subject.contains(text)
        }

    /** Waits for a new message to [address] carrying a token (verification or password reset) and returns it. */
    fun awaitToken(
        address: String,
        known: Set<String> = emptySet(),
    ): String = tokenIn(awaitMessageTo(address, known) { tokenIn(it) != null }).orEmpty()

    /**
     * Waits for the one-time phone verification code sent by SMS to [phoneNumber]. The SMS simulator of the
     * notification service is expected to mirror messages into Mailpit (quickstart: "email and SMS sink"); the
     * search therefore looks for the number anywhere in a message.
     */
    fun awaitSmsCode(phoneNumber: String): String {
        var code: String? = null
        Awaitility
            .await("an SMS verification code for the phone number")
            .atMost(Budgets.notification)
            .pollInterval(Duration.ofSeconds(1))
            .until {
                code =
                    search(phoneNumber.removePrefix("+"))
                        .mapNotNull { it.string("ID") }
                        .firstNotNullOfOrNull { SMS_CODE.find(message(it).text)?.groupValues?.get(1) }
                code != null
            }
        return checkNotNull(code)
    }

    /** Makes Mailpit refuse every recipient (chaos trigger), so that the email channel fails. */
    fun refuseEveryMessage() {
        val response = Http.putJson(URI.create("$baseUrl/api/v1/chaos"), mapOf("Recipient" to trigger(PROBABLE)))
        check(response.statusCode() == Status.OK) {
            "Mailpit chaos is not available (HTTP ${response.statusCode()}): start Mailpit with MP_ENABLE_CHAOS=true"
        }
    }

    /** Switches every chaos trigger off again. */
    fun acceptEveryMessage() {
        Http.putJson(
            URI.create("$baseUrl/api/v1/chaos"),
            mapOf("Sender" to trigger(0), "Recipient" to trigger(0), "Authentication" to trigger(0)),
        )
    }

    private fun newMessages(
        address: String,
        known: Set<String>,
    ): List<MailMessage> =
        messagesTo(address)
            .mapNotNull { it.string("ID") }
            .filterNot { it in known }
            .map { message(it) }

    private fun search(query: String): List<JsonNode> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val response = Http.get(URI.create("$baseUrl/api/v1/search?query=$encoded&limit=$PAGE"))
        check(response.statusCode() == Status.OK) { "Mailpit search failed: HTTP ${response.statusCode()}" }
        return Json.read(response.body()).list("messages")
    }

    private fun trigger(probability: Int) = mapOf("ErrorCode" to SMTP_REFUSED, "Probability" to probability)

    private companion object {
        const val PAGE = 50
        const val SMTP_REFUSED = 451
        const val PROBABLE = 100
        val TAG = Regex("<[^>]+>")
        val TOKEN_IN_LINK = Regex("""[?&]token=([A-Za-z0-9._~%-]+)""")
        val TOKEN_IN_TEXT = Regex("""(?i)\btoken\b\W{1,3}([A-Za-z0-9._~-]{8,})""")
        val SMS_CODE = Regex("""\b(\d{6})\b""")

        fun tokenIn(message: MailMessage): String? {
            val linked = TOKEN_IN_LINK.find(message.text)?.groupValues?.get(1)
            return linked?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                ?: TOKEN_IN_TEXT.find(message.text)?.groupValues?.get(1)
        }
    }
}

package com.ecommerce.gateway.browser

import com.ecommerce.gateway.browser.BrowserJson.text
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/** The one JSON mapper of the browser package (tree model only; no Spring, no logging). */
internal object BrowserJson {
    val mapper: JsonMapper = JsonMapper.builder().build()

    /** The JSON object in [bytes], or null when they are not one. */
    fun readObject(bytes: ByteArray): JsonNode? =
        try {
            mapper.readTree(bytes).takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }

    /** The string member [field], or null when absent or not a string. */
    fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()
}

/**
 * The JSON documents exchanged with identity and with the browser in cookie mode: identity's `TokenPair` and
 * `RefreshRequest` (identity.yaml) and the tokenless `SessionSummary` `{expiresAt, roles}` the browser receives
 * (gateway-browser-session.yaml).
 */
object SessionJson {
    private const val ACCESS_TOKEN = "accessToken"
    private const val REFRESH_TOKEN = "refreshToken"
    private const val EXPIRES_AT = "expiresAt"
    private const val ROLES = "roles"

    /** The token pair of an identity 200 body, or null when the body is not one. */
    fun tokenPair(body: ByteArray): TokenPair? {
        val node = BrowserJson.readObject(body) ?: return null
        val access = node.text(ACCESS_TOKEN)
        val refresh = node.text(REFRESH_TOKEN)
        return if (access == null || refresh == null) null else TokenPair(access, refresh)
    }

    /** Identity's `RefreshRequest` for [refreshToken]. */
    fun refreshRequest(refreshToken: String): ByteArray =
        BrowserJson.mapper.writeValueAsBytes(BrowserJson.mapper.createObjectNode().put(REFRESH_TOKEN, refreshToken))

    /** `{"expiresAt": "<instant>", "roles": [...]}`, roles sorted; never a token, account id or e-mail. */
    fun summary(
        expiresAt: Instant,
        roles: Set<String>,
    ): ByteArray {
        val node = BrowserJson.mapper.createObjectNode().put(EXPIRES_AT, expiresAt.toString())
        node.putArray(ROLES).also { array -> roles.sorted().forEach(array::add) }
        return BrowserJson.mapper.writeValueAsBytes(node)
    }
}

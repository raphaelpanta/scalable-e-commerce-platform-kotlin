package com.ecommerce.gateway.browser

import com.ecommerce.gateway.browser.BrowserJson.text
import tools.jackson.databind.JsonNode
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM sealing with the JDK provider (data-model.md section 4.1, "Sealing rules"):
 *
 * - wire format `<keyId>.<Base64url(nonce || ciphertext || tag)>` without padding;
 * - a fresh 12-byte random nonce on every sealing;
 * - the key id is bound as associated data, so a value re-labelled with another key id fails;
 * - [keys] is the key set for rotation: [activeKeyId] seals, every key unseals its own id, an unknown id fails;
 * - the payload is compact JSON of [SealedSession]; cart tokens are sealed as plain UTF-8.
 *
 * Nothing here logs: a failure is `null`, never a message with the value.
 */
class AesGcmSessionSealer(
    private val keys: Map<String, SecretKey>,
    private val activeKeyId: String,
    private val random: SecureRandom = SecureRandom(),
) : SessionSealer {
    init {
        require(keys.isNotEmpty()) { "at least one sealing key is required" }
        require(activeKeyId in keys) { "the active key id '$activeKeyId' is not in the key set" }
        keys.keys.forEach { id ->
            require(id.isNotEmpty() && SEPARATOR !in id) { "a key id must be non-empty and contain no '.'" }
        }
        keys.values.forEach { key ->
            require(key.encoded.size == KEY_BYTES) { "sealing keys must be $KEY_BYTES bytes" }
        }
    }

    override fun seal(session: SealedSession): String = sealBytes(SessionCodec.encode(session))

    override fun unseal(value: String): SealedSession? = unsealBytes(value)?.let(SessionCodec::decode)

    override fun sealToken(token: String): String = sealBytes(token.toByteArray(Charsets.UTF_8))

    override fun unsealToken(value: String): String? = unsealBytes(value)?.toString(Charsets.UTF_8)

    private fun sealBytes(plaintext: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getValue(activeKeyId), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(activeKeyId.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext)
        return activeKeyId + SEPARATOR + BASE64URL.encodeToString(nonce + sealed)
    }

    private fun unsealBytes(value: String): ByteArray? {
        val separator = value.indexOf(SEPARATOR)
        val keyId = if (separator > 0) value.substring(0, separator) else return null
        val key = keys[keyId]
        val bytes = decodeUnpadded(value.substring(separator + 1))
        return if (key == null || bytes == null || bytes.size < MIN_SEALED_BYTES) null else open(key, keyId, bytes)
    }

    private fun open(
        key: SecretKey,
        keyId: String,
        bytes: ByteArray,
    ): ByteArray? =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.updateAAD(keyId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        private const val MIN_SEALED_BYTES = NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATOR = '.'
        private val BASE64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val BASE64URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()

        /** The bytes of an unpadded Base64url string, or null for anything else (padding included). */
        private fun decodeUnpadded(value: String): ByteArray? =
            if (value.isEmpty() || value.endsWith("=")) {
                null
            } else {
                try {
                    BASE64URL_DECODER.decode(value)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
    }
}

/** Compact JSON of a [SealedSession]; an unreadable or incomplete document decodes to `null`. */
object SessionCodec {
    private const val ACCESS_TOKEN = "accessToken"
    private const val REFRESH_TOKEN = "refreshToken"
    private const val ACCOUNT_ID = "accountId"
    private const val ROLES = "roles"
    private const val LAST_SEEN_AT = "lastSeenAt"
    private const val ISSUED_AT = "issuedAt"

    fun encode(session: SealedSession): ByteArray {
        val node = BrowserJson.mapper.createObjectNode()
        node.put(ACCESS_TOKEN, session.accessToken)
        node.put(REFRESH_TOKEN, session.refreshToken)
        node.put(ACCOUNT_ID, session.accountId)
        node.putArray(ROLES).also { roles -> session.roles.sorted().forEach(roles::add) }
        node.put(LAST_SEEN_AT, session.lastSeenAt.toString())
        node.put(ISSUED_AT, session.issuedAt.toString())
        return BrowserJson.mapper.writeValueAsBytes(node)
    }

    @Suppress("ReturnCount") // one guard clause per field of the payload
    fun decode(bytes: ByteArray): SealedSession? {
        val node = BrowserJson.readObject(bytes) ?: return null
        return SealedSession(
            accessToken = node.text(ACCESS_TOKEN) ?: return null,
            refreshToken = node.text(REFRESH_TOKEN) ?: return null,
            accountId = node.text(ACCOUNT_ID) ?: return null,
            roles = node.strings(ROLES) ?: return null,
            lastSeenAt = node.text(LAST_SEEN_AT)?.let(::instant) ?: return null,
            issuedAt = node.text(ISSUED_AT)?.let(::instant) ?: return null,
        )
    }

    private fun instant(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun JsonNode.strings(field: String): Set<String>? =
        get(field)
            ?.takeIf { it.isArray }
            ?.values()
            ?.takeIf { values -> values.all { it.isString } }
            ?.map { it.stringValue() }
            ?.toSet()
}

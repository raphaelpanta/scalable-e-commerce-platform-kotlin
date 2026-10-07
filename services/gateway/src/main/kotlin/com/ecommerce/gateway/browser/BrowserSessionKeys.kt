package com.ecommerce.gateway.browser

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The sealing key of `BROWSER_SESSION_KEY` (32 random bytes, Base64), shared by every gateway replica. Outside the
 * `dev` and `test` profiles a missing or malformed key stops the start-up, like identity's `IDENTITY_SIGNING_KEY`;
 * under those profiles a throw-away key is generated per process. The key id of the configured key is [ACTIVE_KEY_ID].
 */
object BrowserSessionKeys {
    const val ACTIVE_KEY_ID = "k1"
    const val KEY_BYTES = AesGcmSessionSealer.KEY_BYTES
    private const val ALGORITHM = "AES"

    private const val MISSING_KEY: String =
        "BROWSER_SESSION_KEY is not set: every gateway instance must seal browser cookies with one shared " +
            "AES-256 key. Generate one with `openssl rand -base64 32`, or activate the Spring profile dev or test " +
            "for a throw-away key (docs/gateway.md, \"Browser session\")."

    /** The configured key, or a generated one when [generationAllowed] and none is configured. */
    fun configured(
        encoded: String,
        generationAllowed: Boolean,
    ): SecretKey =
        when {
            encoded.isNotBlank() -> decode(encoded)
            generationAllowed -> generate()
            else -> error(MISSING_KEY)
        }

    /** A fresh random key. */
    fun generate(): SecretKey = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), ALGORITHM)

    /** The key of [encoded] (Base64, standard or URL alphabet), which must hold exactly [KEY_BYTES] bytes. */
    fun decode(encoded: String): SecretKey {
        val bytes =
            try {
                Base64.getMimeDecoder().decode(encoded.trim())
            } catch (invalid: IllegalArgumentException) {
                throw IllegalStateException("BROWSER_SESSION_KEY is not valid Base64", invalid)
            }
        check(bytes.size == KEY_BYTES) {
            "BROWSER_SESSION_KEY must decode to exactly $KEY_BYTES bytes (AES-256), not ${bytes.size}"
        }
        return SecretKeySpec(bytes, ALGORITHM)
    }
}

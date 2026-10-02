package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.observability.Pii
import com.ecommerce.platform.observability.PiiMasking
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * A single-use secret (verification, password reset, anonymous cart): 256 random bits, url-safe Base64 without
 * padding (43 characters). Only [hash] is ever stored; a presented token is checked with [matches], which compares
 * in constant time. `toString()` never reveals the token.
 */
@Pii
@JvmInline
value class SecretToken private constructor(
    val value: String,
) {
    /** Lower-case hex SHA-256 of the token, the only form that is persisted. */
    fun hash(): String = sha256Hex(value)

    /** True when this token hashes to [storedHash]; constant time in the hash length. */
    fun matches(storedHash: String): Boolean = constantTimeEquals(hash(), storedHash)

    override fun toString(): String = "SecretToken(${PiiMasking.MASK})"

    companion object {
        /** Random bytes per token (256 bits). */
        const val BYTES: Int = 32

        /** Characters of the encoded token. */
        const val LENGTH: Int = 43
        private val FORMAT = Regex("[A-Za-z0-9_-]{$LENGTH}")
        private val DEFAULT_RANDOM = SecureRandom()
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** A fresh token from [random]. */
        fun generate(random: SecureRandom = DEFAULT_RANDOM): SecretToken {
            val bytes = ByteArray(BYTES)
            random.nextBytes(bytes)
            return SecretToken(ENCODER.encodeToString(bytes))
        }

        /** Accepts a presented token of the generated shape (43 url-safe Base64 characters). */
        fun of(
            raw: String,
            field: String = "token",
        ): Validated<SecretToken> =
            if (FORMAT.matches(raw)) {
                SecretToken(raw).right()
            } else {
                ValidationError(field, "is not a valid token").left()
            }

        /** Lower-case hex SHA-256 of the UTF-8 bytes of [value]. */
        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }

        /** Compares two strings without short-circuiting on the first difference (timing-safe). */
        fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

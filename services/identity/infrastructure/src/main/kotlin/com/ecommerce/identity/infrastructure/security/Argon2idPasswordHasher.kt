package com.ecommerce.identity.infrastructure.security

import com.ecommerce.identity.application.PasswordHasher
import com.ecommerce.identity.domain.Password
import com.ecommerce.identity.domain.PasswordHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Outbound adapter: Argon2id (RFC 9106) password hashes in the PHC string format
 * `$argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<hash>` (Base64 without padding), with a random 16-byte
 * salt per hash and the OWASP baseline cost (19 MiB, 2 iterations, 1 lane). Verification reads the cost from the
 * stored string, so stronger parameters can be introduced without invalidating older hashes.
 *
 * Hashing is deliberately slow CPU work: it runs on [dispatcher] (the bounded Default pool), never on a Netty or
 * Reactor event loop. A missing hash is verified against a dummy so that unknown accounts cost the same time.
 */
class Argon2idPasswordHasher(
    private val cost: Cost = Cost(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {
    /** Argon2id cost parameters. */
    data class Cost(
        val memoryKib: Int = DEFAULT_MEMORY_KIB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = 1,
    )

    private val dummy: String by lazy { encode("dummy password for unknown accounts") }

    override suspend fun hash(password: Password): PasswordHash =
        withContext(dispatcher) {
            PasswordHash(encode(password.value))
        }

    override suspend fun verify(
        raw: String,
        hash: PasswordHash?,
    ): Boolean =
        withContext(dispatcher) {
            val matches = matches(raw, hash?.value ?: dummy)
            hash != null && matches
        }

    /** A new PHC string for [raw]. */
    fun encode(raw: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(raw, salt, cost)
        return "\$argon2id\$v=$VERSION\$m=${cost.memoryKib},t=${cost.iterations},p=${cost.parallelism}" +
            "\$${ENCODER.encodeToString(salt)}\$${ENCODER.encodeToString(derived)}"
    }

    /** True when [raw] hashes to the PHC string [encoded]; false for anything that is not an Argon2id PHC string. */
    fun matches(
        raw: String,
        encoded: String,
    ): Boolean {
        val match = PHC.matchEntire(encoded) ?: return false
        val groups = match.groupValues
        val stored = DECODER.decode(groups[HASH_GROUP])
        val cost = Cost(groups[MEMORY_GROUP].toInt(), groups[ITERATIONS_GROUP].toInt(), groups[LANES_GROUP].toInt())
        val derived = derive(raw, DECODER.decode(groups[SALT_GROUP]), cost, stored.size)
        return MessageDigest.isEqual(derived, stored)
    }

    private fun derive(
        raw: String,
        salt: ByteArray,
        cost: Cost,
        length: Int = HASH_BYTES,
    ): ByteArray {
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(cost.memoryKib)
                .withIterations(cost.iterations)
                .withParallelism(cost.parallelism)
                .withSalt(salt)
                .build()
        val output = ByteArray(length)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(raw.toByteArray(Charsets.UTF_8), output)
        return output
    }

    companion object {
        const val DEFAULT_MEMORY_KIB: Int = 19_456
        const val DEFAULT_ITERATIONS: Int = 2
        private const val VERSION = 19
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val MEMORY_GROUP = 1
        private const val ITERATIONS_GROUP = 2
        private const val LANES_GROUP = 3
        private const val SALT_GROUP = 4
        private const val HASH_GROUP = 5
        private val ENCODER: Base64.Encoder = Base64.getEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getDecoder()
        private val PHC =
            Regex(
                "\\\$argon2id\\\$v=19\\\$m=(\\d{1,7}),t=(\\d{1,3}),p=(\\d{1,2})" +
                    "\\\$([A-Za-z0-9+/]{11,})\\\$([A-Za-z0-9+/]{22,})",
            )
    }
}

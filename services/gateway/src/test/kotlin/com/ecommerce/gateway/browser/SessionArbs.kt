package com.ecommerce.gateway.browser

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val MAX_EPOCH_SECOND = 4_102_444_800L
private const val MAX_NANO = 999_999_999
private const val MAX_TOKEN_LENGTH = 200
private const val MAX_ROLES = 2

/** Generators of the browser-session model (constitution V: fixtures come from generators, not object mothers). */
object SessionArbs {
    val instant: Arb<Instant> =
        Arb.bind(Arb.long(0L..MAX_EPOCH_SECOND), Arb.int(0..MAX_NANO)) { seconds, nanos ->
            Instant.ofEpochSecond(seconds, nanos.toLong())
        }

    val token: Arb<String> = Arb.string(1..MAX_TOKEN_LENGTH)

    val roles: Arb<Set<String>> = Arb.set(Arb.element("shopper", "operator"), 0..MAX_ROLES)

    val accountId: Arb<String> = Arb.uuid().map(Any::toString)

    val session: Arb<SealedSession> =
        Arb.bind(token, token, accountId, roles, instant, instant) { access, refresh, account, roles, seen, issued ->
            SealedSession(access, refresh, account, roles, seen, issued)
        }

    val key: Arb<SecretKey> =
        Arb.byteArray(Arb.constant(AesGcmSessionSealer.KEY_BYTES), Arb.byte()).map { SecretKeySpec(it, "AES") }

    /** An unsigned compact JWS (`alg: none`) carrying `sub`, `roles` and `exp`; enough for claim parsing. */
    fun unsignedToken(
        subject: String,
        roles: Collection<String>,
        expiresAt: Instant,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val roleList = roles.joinToString(",") { "\"$it\"" }
        val payload = """{"sub":"$subject","roles":[$roleList],"exp":${expiresAt.epochSecond}}"""
        return "$header.${encoder.encodeToString(payload.toByteArray())}."
    }
}

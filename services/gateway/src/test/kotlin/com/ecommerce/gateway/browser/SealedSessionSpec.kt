package com.ecommerce.gateway.browser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val K1 = "k1"
private const val K2 = "k2"
private const val K3 = "k3"
private const val TAG_BYTES = AesGcmSessionSealer.TAG_BITS / Byte.SIZE_BITS
private const val MAX_PADDING_LENGTH = 5_000
private const val SHORT_KEY_BYTES = 16
private val WIRE_FORMAT = Regex("^k1\\.[A-Za-z0-9_-]+$")

private fun keyOf(seed: Byte): SecretKey = SecretKeySpec(ByteArray(AesGcmSessionSealer.KEY_BYTES) { seed }, "AES")

private fun sealer(
    vararg keys: Pair<String, SecretKey>,
    active: String = keys.first().first,
) = AesGcmSessionSealer(keys.toMap(), active)

private fun body(sealed: String): ByteArray = Base64.getUrlDecoder().decode(sealed.substringAfter('.'))

private fun relabel(
    sealed: String,
    keyId: String,
): String = keyId + "." + sealed.substringAfter('.')

/** T013: sealing rules of data-model.md section 4.1 as properties over arbitrary sessions. */
class SealedSessionSpec :
    FunSpec({
        val sealer = sealer(K1 to keyOf(1))

        test("a session round-trips through sealing, for any payload") {
            checkAll(SessionArbs.session) { session ->
                sealer.unseal(sealer.seal(session)) shouldBe session
            }
        }

        test("the wire format is <keyId>.<Base64url(nonce || ciphertext || tag)> without padding") {
            checkAll(SessionArbs.session) { session ->
                val sealed = sealer.seal(session)
                sealed shouldMatch WIRE_FORMAT
                sealed shouldNotContain "="
                body(sealed).size shouldBe
                    AesGcmSessionSealer.NONCE_BYTES + SessionCodec.encode(session).size + TAG_BYTES
            }
        }

        test("every sealing draws a fresh 12-byte nonce, so the same session never seals twice to the same value") {
            checkAll(SessionArbs.session) { session ->
                val first = body(sealer.seal(session))
                val second = body(sealer.seal(session))
                first.copyOf(AesGcmSessionSealer.NONCE_BYTES) shouldNotBe second.copyOf(AesGcmSessionSealer.NONCE_BYTES)
                first shouldNotBe second
            }
        }

        test("a flipped byte anywhere in the sealed bytes fails to unseal") {
            checkAll(SessionArbs.session, Arb.int()) { session, position ->
                val sealed = sealer.seal(session)
                val bytes = body(sealed)
                val index = Math.floorMod(position, bytes.size)
                bytes[index] = (bytes[index].toInt() xor 1).toByte()
                val tampered = K1 + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                sealer.unseal(tampered).shouldBeNull()
            }
        }

        test("an unknown key id, a missing key id, padding or a malformed value fail to unseal") {
            checkAll(SessionArbs.session) { session ->
                val sealed = sealer.seal(session)
                sealer.unseal(relabel(sealed, K3)).shouldBeNull()
                sealer.unseal(sealed.substringAfter('.')).shouldBeNull()
                sealer.unseal(".${sealed.substringAfter('.')}").shouldBeNull()
                sealer.unseal("$sealed=").shouldBeNull()
                sealer.unseal("$K1.").shouldBeNull()
                sealer.unseal("$K1.!!!").shouldBeNull()
            }
            checkAll(Arb.string()) { garbage -> sealer.unseal(garbage).shouldBeNull() }
        }

        test("the key id is bound as associated data: the same key under another id does not unseal") {
            val sameKeyTwice = sealer(K1 to keyOf(1), K2 to keyOf(1))
            checkAll(SessionArbs.session) { session ->
                val sealed = sameKeyTwice.seal(session)
                sameKeyTwice.unseal(sealed) shouldBe session
                sameKeyTwice.unseal(relabel(sealed, K2)).shouldBeNull()
            }
        }

        test("key rotation: a key set unseals values sealed under any of its ids, an unknown id fails") {
            val old = sealer(K1 to keyOf(1))
            val rotated = sealer(K1 to keyOf(1), K2 to keyOf(2), active = K2)
            checkAll(SessionArbs.session) { session ->
                rotated.unseal(old.seal(session)) shouldBe session
                rotated.seal(session).substringBefore('.') shouldBe K2
                rotated.unseal(rotated.seal(session)) shouldBe session
                old.unseal(rotated.seal(session)).shouldBeNull()
                rotated.unseal(relabel(rotated.seal(session), K3)).shouldBeNull()
            }
        }

        test("a cookie over 4 KiB is refused instead of truncated") {
            checkAll(Arb.int(0..MAX_PADDING_LENGTH), SessionArbs.session) { padding, base ->
                val session = base.copy(accessToken = "a".repeat(padding))
                val value = sealer.seal(session)
                CookieName.entries.forEach { name ->
                    val secure = if (name.secure) "Secure; " else ""
                    val expected = "${name.value}=$value; HttpOnly; ${secure}SameSite=${name.kind.sameSite}; Path=/"
                    BrowserCookies.set(name, value) shouldBe
                        expected.takeIf { it.toByteArray().size <= BrowserCookies.MAX_COOKIE_BYTES }
                }
            }
        }

        test("a cart token round-trips and is never mistaken for a session") {
            checkAll(SessionArbs.token) { token ->
                val sealed = sealer.sealToken(token)
                sealer.unsealToken(sealed) shouldBe token
                sealer.unseal(sealed).shouldBeNull()
            }
        }

        test("an unreadable or incomplete payload decodes to nothing (named edge cases)") {
            val valid =
                SessionCodec.encode(
                    SealedSession("a", "r", "id", setOf("shopper"), Instant.EPOCH, Instant.EPOCH),
                )
            SessionCodec.decode(valid).shouldNotBeNull()
            val epoch = "\"1970-01-01T00:00:00Z\""
            val complete =
                mapOf(
                    "accessToken" to "\"a\"",
                    "refreshToken" to "\"r\"",
                    "accountId" to "\"id\"",
                    "roles" to "[\"shopper\"]",
                    "lastSeenAt" to epoch,
                    "issuedAt" to epoch,
                )

            /** The complete payload with [overrides] replacing members (an empty value removes one). */
            fun payload(vararg overrides: Pair<String, String>): String =
                (complete + overrides)
                    .filterValues(String::isNotEmpty)
                    .entries
                    .joinToString(",", "{", "}") { (name, value) -> "\"$name\":$value" }
            SessionCodec.decode(payload().toByteArray()).shouldNotBeNull()
            listOf(
                "[]",
                "{",
                "{}",
                payload("lastSeenAt" to "\"x\""),
                payload("issuedAt" to "null"),
                payload("roles" to "[1]"),
                payload("roles" to "\"shopper\""),
                payload("roles" to ""),
                payload("accessToken" to "1"),
                payload("refreshToken" to ""),
                payload("accountId" to "{}"),
            ).forEach { json -> SessionCodec.decode(json.toByteArray()).shouldBeNull() }
        }

        test("a sealer refuses an empty key set, an unknown active id, a dotted id or a key of the wrong size") {
            shouldThrow<IllegalArgumentException> { AesGcmSessionSealer(emptyMap(), K1) }
            shouldThrow<IllegalArgumentException> { sealer(K1 to keyOf(1), active = K2) }
            shouldThrow<IllegalArgumentException> { sealer("k.1" to keyOf(1)) }
            shouldThrow<IllegalArgumentException> { sealer("" to keyOf(1)) }
            shouldThrow<IllegalArgumentException> {
                sealer(K1 to SecretKeySpec(ByteArray(SHORT_KEY_BYTES), "AES"))
            }
        }

        test("the session value never shows its tokens") {
            checkAll(SessionArbs.session) { base ->
                val session = base.copy(accessToken = "ACCESS-SECRET", refreshToken = "REFRESH-SECRET")
                session.toString() shouldBe
                    "SealedSession(roles=${session.roles}, lastSeenAt=${session.lastSeenAt}, issuedAt=${session.issuedAt})"
                session.toString() shouldNotContain "SECRET"
                TokenPair(session.accessToken, session.refreshToken).toString() shouldNotContain "SECRET"
            }
        }
    })

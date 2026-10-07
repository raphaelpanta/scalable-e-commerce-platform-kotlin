package com.ecommerce.gateway.config

import com.ecommerce.gateway.browser.BrowserSessionKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.net.URI
import java.time.Duration
import java.util.Base64

private const val MAX_WRONG_LENGTH = 64
private const val IDLE_MINUTES = 30L
private const val REFRESH_AHEAD_SECONDS = 60L
private const val CART_COOKIE_DAYS = 30L
private const val REFRESH_TIMEOUT_SECONDS = 5L

/** T016: `gateway.browser-session.*` defaults and the `BROWSER_SESSION_KEY` start-up rule. */
class BrowserSessionPropertiesTest :
    FunSpec({
        test("defaults follow gateway-routes.md: 30 min idle, 60 s refresh-ahead, 30-day cart cookie, no key") {
            val properties = GatewayProperties.BrowserSessionProperties()
            properties.key shouldBe ""
            properties.identityUrl shouldBe URI("http://localhost:8080")
            properties.idleTimeout shouldBe Duration.ofMinutes(IDLE_MINUTES)
            properties.refreshAhead shouldBe Duration.ofSeconds(REFRESH_AHEAD_SECONDS)
            properties.cartCookieMaxAge shouldBe Duration.ofDays(CART_COOKIE_DAYS)
            properties.refreshTimeout shouldBe Duration.ofSeconds(REFRESH_TIMEOUT_SECONDS)
            GatewayProperties(
                GatewayProperties.Jwt(URI("http://identity/jwks"), issuer = "issuer", audience = "aud"),
            ).browserSession shouldBe properties
        }

        test("the key never appears in the properties' string form") {
            val secret = Base64.getEncoder().encodeToString(ByteArray(BrowserSessionKeys.KEY_BYTES) { 7 })
            val shown = GatewayProperties.BrowserSessionProperties(key = secret).toString()
            shown shouldNotContain secret
            shown shouldContain "key=<set>"
            GatewayProperties.BrowserSessionProperties().toString() shouldContain "key=<generated>"
        }

        test("a configured key must be Base64 of exactly 32 bytes, in any profile") {
            checkAll(
                Arb.byteArray(Arb.int(BrowserSessionKeys.KEY_BYTES..BrowserSessionKeys.KEY_BYTES), Arb.byte()),
            ) { bytes ->
                val standard = Base64.getEncoder().encodeToString(bytes)
                BrowserSessionKeys.configured(standard, generationAllowed = false).encoded shouldBe bytes
                BrowserSessionKeys.configured(" $standard\n", generationAllowed = true).encoded shouldBe bytes
            }
            // Length 0 encodes to the blank "not configured" value, which is the generation rule's case below.
            val wrongLength = Arb.int(1..MAX_WRONG_LENGTH).filter { it != BrowserSessionKeys.KEY_BYTES }
            checkAll(Arb.byteArray(wrongLength, Arb.byte())) { bytes ->
                val encoded = Base64.getEncoder().encodeToString(bytes)
                shouldThrow<IllegalStateException> { BrowserSessionKeys.configured(encoded, generationAllowed = true) }
                    .message shouldContain "exactly ${BrowserSessionKeys.KEY_BYTES} bytes"
            }
            shouldThrow<IllegalStateException> {
                BrowserSessionKeys.configured(
                    "not base64!",
                    generationAllowed = false,
                )
            }.message shouldContain "Base64"
        }

        test("without a key the gateway starts only under dev or test, with a fresh key per process") {
            val failure =
                shouldThrow<IllegalStateException> { BrowserSessionKeys.configured("", generationAllowed = false) }
            failure.message shouldContain "BROWSER_SESSION_KEY is not set"
            failure.message shouldContain "openssl rand -base64 32"
            shouldThrow<IllegalStateException> { BrowserSessionKeys.configured("  ", generationAllowed = false) }
            val generated = BrowserSessionKeys.configured("", generationAllowed = true)
            generated.encoded.size shouldBe BrowserSessionKeys.KEY_BYTES
            generated.algorithm shouldBe "AES"
            generated.encoded shouldNotBe BrowserSessionKeys.configured("", generationAllowed = true).encoded
            BrowserSessionKeys.ACTIVE_KEY_ID shouldBe "k1"
        }
    })

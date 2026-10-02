package com.ecommerce.platform.core.values

import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.testing.PlatformArbs
import com.ecommerce.platform.testing.ProblemAssertions.shouldBeValid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.security.SecureRandom
import java.util.UUID

private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

class CorrelationIdSpec :
    FunSpec({
        val reason = ValidationError("correlationId", "must be 1 to 64 letters, digits or hyphens")

        test("1 to 64 letters, digits and hyphens are accepted unchanged") {
            checkAll(PlatformArbs.correlationIdText()) { raw ->
                CorrelationId.of(raw).shouldBeValid().value shouldBe raw
                CorrelationId.sanitise(raw) shouldBe
                    CorrelationId.Sanitised(CorrelationId.of(raw).shouldBeValid(), null)
            }
            CorrelationId.of("a".repeat(64)).isRight() shouldBe true
            CorrelationId.of("3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13").shouldBeValid().toString() shouldBe
                "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
        }

        test("empty, oversized and foreign characters are rejected") {
            CorrelationId.of("").leftOrNull() shouldBe reason
            CorrelationId.of("a".repeat(65)).leftOrNull() shouldBe reason
            listOf("a b", "a_b", "a.b", "é", "a\nb").forEach { CorrelationId.of(it).leftOrNull() shouldBe reason }
            CorrelationId.of("", "X-Correlation-Id").leftOrNull()?.field shouldBe "X-Correlation-Id"
        }

        test("a missing value is generated without an original") {
            listOf(null, "").forEach { raw ->
                val sanitised = CorrelationId.sanitise(raw)
                sanitised.value.value shouldMatch UUID_PATTERN
                sanitised.original shouldBe null
                sanitised.replaced shouldBe false
            }
            CorrelationId.generate() shouldNotBe CorrelationId.generate()
        }

        test("a malformed value is replaced and kept log-safe") {
            val sanitised = CorrelationId.sanitise("bad id\r\nforged=1")
            sanitised.value.value shouldMatch UUID_PATTERN
            sanitised.replaced shouldBe true
            sanitised.original shouldBe "bad id??forged=1"
            CorrelationId.sanitise(" ~\u007f\u001f").original shouldBe " ~??"
            CorrelationId.sanitise("x".repeat(65)).original shouldBe "x".repeat(65)
            CorrelationId.sanitise("x".repeat(129) + "!").original shouldBe "x".repeat(128)
        }
    })

class IdempotencyKeySpec :
    FunSpec({
        test("1 to 128 visible ASCII characters are kept verbatim") {
            checkAll(PlatformArbs.idempotencyKeyText()) { raw ->
                IdempotencyKey.of(raw).shouldBeValid().value shouldBe raw
            }
            IdempotencyKey.of("k".repeat(128)).isRight() shouldBe true
            IdempotencyKey.of("6f1c2d3e").shouldBeValid().toString() shouldBe "6f1c2d3e"
        }

        test("blank, oversized, whitespace and non-ASCII keys are rejected") {
            IdempotencyKey.of("").leftOrNull() shouldBe ValidationError("Idempotency-Key", "must not be blank")
            IdempotencyKey.of("k".repeat(129)).leftOrNull() shouldBe
                ValidationError("Idempotency-Key", "must be at most 128 characters")
            listOf("a b", "a\tb", "café", " a").forEach {
                IdempotencyKey.of(it).leftOrNull() shouldBe
                    ValidationError("Idempotency-Key", "must be printable ASCII without whitespace")
            }
        }
    })

class SecretTokenSpec :
    FunSpec({
        test("generated tokens are 43 url-safe characters and parse back") {
            repeat(50) {
                val token = SecretToken.generate()
                token.value shouldMatch "[A-Za-z0-9_-]{43}"
                SecretToken.of(token.value).shouldBeValid() shouldBe token
            }
            SecretToken.generate() shouldNotBe SecretToken.generate()
        }

        test("the given random source is used") {
            val seeded = { SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) } }
            SecretToken.generate(seeded()) shouldBe SecretToken.generate(seeded())
        }

        test("only well-formed tokens are accepted") {
            val reason = ValidationError("token", "is not a valid token")
            listOf("a".repeat(42), "a".repeat(44), "a".repeat(42) + "+", "a".repeat(42) + "=").forEach {
                SecretToken.of(it).leftOrNull() shouldBe reason
            }
            SecretToken.of("", "resetToken").leftOrNull()?.field shouldBe "resetToken"
        }

        test("hash is lower-case hex SHA-256 and matches compares it") {
            SecretToken.sha256Hex("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            SecretToken.sha256Hex("") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            checkAll(Arb.int()) {
                val token = SecretToken.generate()
                token.hash() shouldBe SecretToken.sha256Hex(token.value)
                token.matches(token.hash()) shouldBe true
                token.matches(SecretToken.generate().hash()) shouldBe false
            }
        }

        test("constant-time comparison is plain equality") {
            SecretToken.constantTimeEquals("abc", "abc") shouldBe true
            SecretToken.constantTimeEquals("abc", "abd") shouldBe false
            SecretToken.constantTimeEquals("abc", "abcd") shouldBe false
        }

        test("toString never shows the token") {
            SecretToken.generate().toString() shouldBe "SecretToken(***)"
        }
    })

class PageRequestSpec :
    FunSpec({
        test("defaults are the first page of 20") {
            PageRequest.of().shouldBeValid() shouldBe PageRequest.FIRST
            PageRequest.FIRST.page shouldBe 0
            PageRequest.FIRST.size shouldBe 20
            PageRequest.fromQuery(null, null).shouldBeValid() shouldBe PageRequest.FIRST
        }

        test("page is at least 0 and size within 1..100") {
            checkAll(PlatformArbs.pageRequest()) { page ->
                page.offset shouldBe page.page.toLong() * page.size
            }
            PageRequest.of(page = -1).leftOrNull() shouldBe ValidationError("page", "must be at least 0")
            PageRequest.of(size = 0).leftOrNull() shouldBe ValidationError("size", "must be at least 1")
            PageRequest.of(size = 1).isRight() shouldBe true
            PageRequest.of(size = 100).isRight() shouldBe true
            PageRequest.of(size = 101).leftOrNull() shouldBe ValidationError("size", "must be at most 100")
            PageRequest.of(page = Int.MAX_VALUE, size = 100).shouldBeValid().offset shouldBe
                Int.MAX_VALUE.toLong() * 100
        }

        test("query parameters are parsed") {
            PageRequest.fromQuery("2", "50").shouldBeValid() shouldBe PageRequest.of(2, 50).shouldBeValid()
            PageRequest.fromQuery("3", null).shouldBeValid() shouldBe PageRequest.of(3, 20).shouldBeValid()
            PageRequest.fromQuery(null, "5").shouldBeValid() shouldBe PageRequest.of(0, 5).shouldBeValid()
            PageRequest.fromQuery("x", "5").leftOrNull() shouldBe ValidationError("page", "must be an integer")
            PageRequest.fromQuery("1", "y").leftOrNull() shouldBe ValidationError("size", "must be an integer")
            PageRequest.fromQuery("1", "500").leftOrNull() shouldBe ValidationError("size", "must be at most 100")
        }
    })

class UuidsSpec :
    FunSpec({
        test("canonical UUIDs parse, in either case") {
            checkAll(Arb.int()) {
                val id = Uuids.random()
                Uuids.parse(id.toString()).shouldBeValid() shouldBe id
                Uuids.parse(id.toString().uppercase()).shouldBeValid() shouldBe id
            }
        }

        test("lenient forms and the nil UUID are rejected") {
            listOf(
                "1-1-1-1-1",
                "",
                "not-a-uuid",
                "3f6c1b2a9d4e4f708a156b2c7d9e0f13",
                " 3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
            ).forEach {
                Uuids
                    .parse(
                        it,
                        "orderId",
                    ).leftOrNull() shouldBe ValidationError("orderId", "must be a UUID")
            }
            Uuids.parse("00000000-0000-0000-0000-000000000000").leftOrNull() shouldBe
                ValidationError("id", "must not be the nil UUID")
            Uuids.nonNil(UUID(0, 1)).isRight() shouldBe true
            Uuids.NIL shouldBe UUID(0, 0)
        }
    })

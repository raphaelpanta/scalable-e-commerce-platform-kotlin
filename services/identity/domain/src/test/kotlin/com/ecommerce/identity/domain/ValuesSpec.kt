package com.ecommerce.identity.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class ValuesSpec :
    FunSpec({
        test("an email is trimmed and lower-cased, and its toString hides the local part") {
            checkAll(arbEmail) { raw ->
                val parsed = Email.of("  ${raw.uppercase()} ").value()
                parsed.value shouldBe raw.lowercase()
                parsed.toString() shouldBe parsed.value.first() + "***@example.test"
            }
        }

        test("an email refuses every malformed shape with a reason on its field") {
            Email.of("").error() shouldBe FieldError("email", "must not be blank")
            Email.of("   ").error().reason shouldBe "must not be blank"
            Email.of("a".repeat(Email.MAX_LENGTH - 12) + "@example.test", "e").error() shouldBe
                FieldError("e", "must be at most 254 characters")
            Email
                .of("a".repeat(Email.MAX_LENGTH - 13) + "@example.test")
                .value()
                .value.length shouldBe Email.MAX_LENGTH
            Email.of("a b@example.test").error().reason shouldBe "must not contain whitespace"
            Email.of("a\u0001b@example.test").error().reason shouldBe "must not contain whitespace"
            Email.of("ab.example.test").error().reason shouldBe "must contain exactly one @"
            Email.of("a@b@example.test").error().reason shouldBe "must contain exactly one @"
            Email.of("@example.test").error().reason shouldBe "must have a local part before the @"
            Email.of("ada@localhost").error().reason shouldBe "must have a domain containing a dot"
            Email.of("ada@.example").error().reason shouldBe "must have a domain containing a dot"
            Email.of("ada@example.").error().reason shouldBe "must have a domain containing a dot"
            Email.of("a@b.c").value().value shouldBe "a@b.c"
        }

        test("a password has 12 to 128 characters and is not the email address") {
            checkAll(Arb.int(0..200)) { length ->
                val result = Password.of("x".repeat(length), email())
                when {
                    length < Password.MIN_LENGTH -> {
                        result.error() shouldBe FieldError("password", "must be at least 12 characters")
                    }

                    length > Password.MAX_LENGTH -> {
                        result.error() shouldBe FieldError("password", "must be at most 128 characters")
                    }

                    else -> {
                        result.value().value shouldBe "x".repeat(length)
                    }
                }
            }
            Password.of("Ada@Example.Test", email("ada@example.test"), "newPassword").error() shouldBe
                FieldError("newPassword", "must not be the email address")
            Password.of(" ada@example.test ", email("ada@example.test")).error().reason shouldBe
                "must not be the email address"
            Password.of("ada@example.test", null).value().value shouldBe "ada@example.test"
            Password.of("ada@example.test", email("grace@example.test")).value().toString() shouldBe "Password(***)"
        }

        test("a display name is optional, trimmed, at most 100 characters and without control characters") {
            checkAll(Arb.string(0..150)) { raw ->
                val trimmed = raw.trim()
                val result = DisplayName.of(raw)
                when {
                    trimmed.isEmpty() -> {
                        result.value().shouldBeNull()
                    }

                    trimmed.length > DisplayName.MAX_LENGTH -> {
                        result.error().reason shouldBe
                            "must be at most 100 characters"
                    }

                    trimmed.any { it.isISOControl() } -> {
                        result.error().reason shouldBe
                            "must not contain control characters"
                    }

                    else -> {
                        result.value()?.value shouldBe trimmed
                    }
                }
            }
            DisplayName.of(null).value().shouldBeNull()
            DisplayName
                .of("a".repeat(DisplayName.MAX_LENGTH))
                .value()
                ?.value
                ?.length shouldBe DisplayName.MAX_LENGTH
            DisplayName.of("x".repeat(DisplayName.MAX_LENGTH + 1), "name").error().field shouldBe "name"
            DisplayName.of("Ada\u0007").error() shouldBe
                FieldError("displayName", "must not contain control characters")
            DisplayName.of("Ada Lovelace").value().toString() shouldBe "A***"
        }

        test("a phone number is E.164 and shows only its last two digits") {
            checkAll(Arb.pattern("\\+[1-9][0-9]{7,14}")) { raw ->
                PhoneNumber.of(" $raw ").value().value shouldBe raw
            }
            listOf("+0123456789", "5511987654321", "+1234567", "+1234567890123456", "+55 11 98765", "").forEach {
                PhoneNumber.of(it).error() shouldBe
                    FieldError("phoneNumber", "must be in E.164 format (+ and 8 to 15 digits)")
            }
            PhoneNumber.of("+12345678").value().value shouldBe "+12345678"
            PhoneNumber.of("+123456789012345").value().value shouldBe "+123456789012345"
            PhoneNumber.of("x", "phone").error().field shouldBe "phone"
            phone("+5511987654321").toString() shouldBe "***21"
        }

        test("roles, statuses and channels round-trip through their codes") {
            Role.entries.forEach { Role.fromCode(it.code) shouldBe it }
            AccountStatus.entries.forEach { AccountStatus.fromCode(it.code) shouldBe it }
            NotificationChannel.entries.forEach { NotificationChannel.fromCode(it.code) shouldBe it }
            TokenPurpose.entries.forEach { TokenPurpose.fromCode(it.code) shouldBe it }
            Role.entries.map { it.code } shouldBe listOf("shopper", "operator")
            AccountStatus.entries.map { it.code } shouldBe listOf("unverified", "active", "deleted")
            NotificationChannel.entries.map { it.code } shouldBe listOf("email", "sms")
            Role.fromCode("admin").shouldBeNull()
            AccountStatus.fromCode("locked").shouldBeNull()
            NotificationChannel.fromCode("push").shouldBeNull()
            TokenPurpose.fromCode("other").shouldBeNull()
        }

        test("a pseudonym is anon- and 8 hex digits of the SHA-256 of the account id, stable per account") {
            checkAll(arbAccountId) { id ->
                val pseudonym = Pseudonym.of(id)
                pseudonym shouldBe Pseudonym.of(id)
                pseudonym.value shouldBe "anon-" + Digests.sha256Hex(id.value.toString()).take(8)
                pseudonym.placeholderEmail shouldBe pseudonym.value + "@anonymised.invalid"
                Email.of(pseudonym.placeholderEmail).value().value shouldBe pseudonym.placeholderEmail
            }
        }

        test("an opaque token has 43 url-safe characters, hashes with SHA-256 and never shows itself") {
            checkAll(Arb.pattern("[A-Za-z0-9_-]{43}")) { raw ->
                val parsed = OpaqueToken.of(raw).value()
                parsed.value shouldBe raw
                parsed.hash() shouldBe TokenHash(Digests.sha256Hex(raw))
                parsed shouldBe OpaqueToken.of(raw).value()
                parsed.hashCode() shouldBe raw.hashCode()
                parsed.toString() shouldNotContain raw
            }
            listOf("a".repeat(42), "a".repeat(44), "a".repeat(42) + "=", "").forEach {
                OpaqueToken.of(it).error() shouldBe FieldError("token", "is not a valid token")
            }
            OpaqueToken.of("x", "refreshToken").error().field shouldBe "refreshToken"
            token('a') shouldNotBe token('b')
            (token('a') as Any) shouldNotBe "a".repeat(OpaqueToken.LENGTH)
        }

        test("a verification code is 6 digits, hashed per account and never shown") {
            checkAll(Arb.pattern("[0-9]{6}"), arbAccountId) { raw, id ->
                val parsed = VerificationCode.of(raw).value()
                parsed.value shouldBe raw
                parsed.hashFor(id) shouldBe TokenHash(Digests.sha256Hex("${id.value}:$raw"))
                parsed.toString() shouldNotContain raw
            }
            listOf("12345", "1234567", "12a456", "").forEach {
                VerificationCode.of(it).error() shouldBe FieldError("code", "must be 6 digits")
            }
            VerificationCode.of("x", "otp").error().field shouldBe "otp"
        }

        test("password hashes never reveal themselves and hashes compare by value") {
            PasswordHash("secret").toString() shouldBe "PasswordHash(***)"
            Digests.sameHash(TokenHash("ab"), TokenHash("ab")) shouldBe true
            Digests.sameHash(TokenHash("ab"), TokenHash("ac")) shouldBe false
            Digests.sha256Hex("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
    })

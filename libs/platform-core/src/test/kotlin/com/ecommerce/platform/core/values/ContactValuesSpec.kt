package com.ecommerce.platform.core.values

import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.testing.PlatformArbs
import com.ecommerce.platform.testing.ProblemAssertions.shouldBeValid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.checkAll

private fun emailError(reason: String) = ValidationError("email", reason)

class EmailSpec :
    FunSpec({
        test("valid addresses are trimmed and lower-cased") {
            checkAll(PlatformArbs.emailText()) { raw ->
                val email = Email.of("  ${raw.uppercase()} ").shouldBeValid()
                email.value shouldBe raw.lowercase()
                email.domain shouldBe raw.lowercase().substringAfter('@')
            }
            Email.of("Ada@Example.COM") shouldBe Email.of("ada@example.com")
        }

        test("the maximum length is 254 characters") {
            val domain = "@example.com"
            Email.of("a".repeat(254 - domain.length) + domain).isRight() shouldBe true
            Email.of("a".repeat(255 - domain.length) + domain).leftOrNull() shouldBe
                emailError("must be at most 254 characters")
        }

        test("each structural rule has its own reason") {
            Email.of("   ").leftOrNull() shouldBe emailError("must not be blank")
            Email.of("ada lovelace@example.com").leftOrNull() shouldBe emailError("must not contain whitespace")
            Email.of("ada@exa\u0007mple.com").leftOrNull() shouldBe emailError("must not contain whitespace")
            Email.of("ada.example.com").leftOrNull() shouldBe emailError("must contain exactly one @")
            Email.of("ada@home@example.com").leftOrNull() shouldBe emailError("must contain exactly one @")
            Email.of("@example.com").leftOrNull() shouldBe emailError("must have a local part before the @")
            Email.of("ada@example").leftOrNull() shouldBe emailError("must have a domain containing a dot")
            Email.of("ada@.example").leftOrNull() shouldBe emailError("must have a domain containing a dot")
            Email.of("ada@example.").leftOrNull() shouldBe emailError("must have a domain containing a dot")
            Email.of("ada@", "contact.email").leftOrNull() shouldBe
                ValidationError("contact.email", "must have a domain containing a dot")
        }

        test("toString masks the local part") {
            Email.of("ada.lovelace@example.com").shouldBeValid().toString() shouldBe "a***@example.com"
            checkAll(PlatformArbs.email()) { email ->
                if (email.value.substringBefore('@').length > 1) email.toString() shouldNotContain email.value
            }
        }
    })

class PhoneNumberSpec :
    FunSpec({
        val reason = ValidationError("phoneNumber", "must be in E.164 format (+ and 8 to 15 digits)")

        test("E.164 numbers are accepted, surrounding blanks ignored") {
            checkAll(PlatformArbs.phoneText()) { raw -> PhoneNumber.of(" $raw ").shouldBeValid().value shouldBe raw }
        }

        test("8 to 15 digits with a non-zero first digit") {
            PhoneNumber.of("+1234567").leftOrNull() shouldBe reason
            PhoneNumber.of("+12345678").isRight() shouldBe true
            PhoneNumber.of("+123456789012345").isRight() shouldBe true
            PhoneNumber.of("+1234567890123456").leftOrNull() shouldBe reason
            PhoneNumber.of("+0234567890").leftOrNull() shouldBe reason
            PhoneNumber.of("5511987654321").leftOrNull() shouldBe reason
            PhoneNumber.of("+55 11 98765-4321").leftOrNull() shouldBe reason
            PhoneNumber.of("x", "sms").leftOrNull()?.field shouldBe "sms"
        }

        test("toString shows only the last two digits") {
            PhoneNumber.of("+5511987654321").shouldBeValid().toString() shouldBe "***21"
        }
    })

class QuantitySpec :
    FunSpec({
        test("1 to 99 are quantities") {
            (1..99).forEach { Quantity.of(it).shouldBeValid().value shouldBe it }
            Quantity.of(0).leftOrNull() shouldBe ValidationError("quantity", "must be at least 1")
            Quantity.of(100).leftOrNull() shouldBe ValidationError("quantity", "must be at most 99")
            Quantity.of(-5, "lines[0].quantity").leftOrNull()?.field shouldBe "lines[0].quantity"
        }

        test("plus stays within the maximum") {
            checkAll(PlatformArbs.quantity(), PlatformArbs.quantity()) { a, b ->
                val sum = a + b
                if (a.value + b.value <= Quantity.MAX) {
                    sum.shouldBeValid().value shouldBe a.value + b.value
                } else {
                    sum.leftOrNull() shouldBe ValidationError("quantity", "must be at most 99")
                }
            }
            Quantity.of(7).shouldBeValid().toString() shouldBe "7"
        }
    })

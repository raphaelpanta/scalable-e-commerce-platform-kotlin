package com.ecommerce.platform.core.values

import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.testing.PlatformArbs
import com.ecommerce.platform.testing.ProblemAssertions.shouldBeValid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.checkAll

@Suppress("LongParameterList")
private fun address(
    recipientName: String = "Ada Lovelace",
    line1: String = "12 Analytical Street",
    line2: String? = "Flat 2",
    city: String = "London",
    region: String? = "England",
    postalCode: String = "N1 9GU",
    countryCode: String = "GB",
) = PostalAddress.of(recipientName, line1, line2, city, region, postalCode, countryCode)

class PostalAddressSpec :
    FunSpec({
        test("fields are trimmed, blank optional fields become null and the country is upper-cased") {
            val address =
                address(
                    recipientName = " Ada ",
                    line2 = "  ",
                    region = null,
                    countryCode = "gb",
                ).shouldBeValid()
            address.recipientName shouldBe "Ada"
            address.line2 shouldBe null
            address.region shouldBe null
            address.countryCode shouldBe "GB"
            address(line2 = " Flat 2 ").shouldBeValid().line2 shouldBe "Flat 2"
        }

        test("every generated address is valid and round-trips") {
            checkAll(PlatformArbs.postalAddress()) { generated ->
                address(
                    generated.recipientName,
                    generated.line1,
                    generated.line2,
                    generated.city,
                    generated.region,
                    generated.postalCode,
                    generated.countryCode,
                ).shouldBeValid() shouldBe generated
            }
        }

        test("length limits are inclusive") {
            address(recipientName = "a".repeat(100)).isRight() shouldBe true
            address(recipientName = "a".repeat(101)).leftOrNull()?.single() shouldBe
                ValidationError("recipientName", "must be at most 100 characters")
            address(line1 = "a".repeat(120)).isRight() shouldBe true
            address(line1 = "a".repeat(121)).leftOrNull()?.single()?.field shouldBe "line1"
            address(line2 = "a".repeat(120)).isRight() shouldBe true
            address(line2 = "a".repeat(121)).leftOrNull()?.single()?.field shouldBe "line2"
            address(city = "a".repeat(80)).isRight() shouldBe true
            address(city = "a".repeat(81)).leftOrNull()?.single()?.field shouldBe "city"
            address(region = "a".repeat(80)).isRight() shouldBe true
            address(region = "a".repeat(81)).leftOrNull()?.single()?.field shouldBe "region"
            address(postalCode = "a".repeat(20)).isRight() shouldBe true
            address(postalCode = "a".repeat(21)).leftOrNull()?.single()?.field shouldBe "postalCode"
        }

        test("all broken rules are reported together") {
            address(recipientName = " ", line1 = "", city = "", postalCode = "", countryCode = "XX")
                .leftOrNull()
                .orEmpty()
                .map { it.field } shouldContainExactly
                listOf("recipientName", "line1", "city", "postalCode", "countryCode")
            address(recipientName = "").leftOrNull()?.single() shouldBe
                ValidationError("recipientName", "must not be blank")
            address(countryCode = "GBR").leftOrNull()?.single() shouldBe
                ValidationError("countryCode", "must be an ISO-3166 alpha-2 country code")
        }

        test("toString masks everything but the country") {
            val text = address().shouldBeValid().toString()
            listOf(
                "Lovelace",
                "Analytical",
                "Flat 2",
                "London",
                "England",
                "N1 9GU",
            ).forEach { text shouldNotContain it }
            text shouldBe
                "PostalAddress(recipientName=A***, line1=***, line2=***, city=L***, region=E***, postalCode=***, countryCode=GB)"
        }
    })

class TextRulesSpec :
    FunSpec({
        test("minimum lengths other than one name the limit") {
            TextRules.trimmedLength("ab", "sku", 3, 32).leftOrNull() shouldBe
                ValidationError("sku", "must be at least 3 characters")
            TextRules.trimmedLength("abc", "sku", 3, 32) shouldBe TextRules.trimmedLength(" abc ", "sku", 3, 32)
            TextRules.trimmedLength("", "name", 1, 2).leftOrNull() shouldBe ValidationError("name", TextRules.BLANK)
        }

        test("visible ASCII excludes space, DEL and non-ASCII") {
            TextRules.isVisibleAscii("!~azAZ09") shouldBe true
            listOf(" ", "\u007f", "é", "\t").forEach { TextRules.isVisibleAscii("a$it") shouldBe false }
        }
    })

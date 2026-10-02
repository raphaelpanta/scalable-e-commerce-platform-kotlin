package com.ecommerce.catalog.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll

private const val VALID_PATTERN = "[a-z][a-z0-9-]{1,38}[a-z0-9]"

private fun valid(raw: String): ServiceName = ServiceName.of(raw).shouldBeInstanceOf<ServiceNameResult.Valid>().name

private fun invalid(raw: String): String = ServiceName.of(raw).shouldBeInstanceOf<ServiceNameResult.Invalid>().reason

class ServiceNameTest :
    FunSpec({
        test("every kebab-case name of 3 to 40 characters is valid and keeps its value") {
            checkAll(Arb.pattern(VALID_PATTERN)) { raw ->
                valid(raw).value shouldBe raw
            }
        }

        test("a name containing an uppercase letter is invalid") {
            checkAll(Arb.pattern("[a-z][a-z0-9-]{0,18}[A-Z][a-z0-9-]{0,18}[a-z0-9]")) { raw ->
                invalid(raw).shouldNotBeBlank()
            }
        }

        test("a name starting with a digit or a hyphen is invalid") {
            checkAll(Arb.pattern("[0-9-][a-z0-9-]{1,38}[a-z0-9]")) { raw ->
                invalid(raw).shouldNotBeBlank()
            }
        }

        test("a name shorter than 3 characters is invalid") {
            checkAll(Arb.pattern("[a-z][a-z0-9]?")) { raw ->
                invalid(raw).shouldNotBeBlank()
            }
        }

        test("a name longer than 40 characters is invalid") {
            checkAll(Arb.pattern("[a-z][a-z0-9-]{39,60}[a-z0-9]")) { raw ->
                invalid(raw).shouldNotBeBlank()
            }
        }

        test("a name ending with a hyphen is invalid") {
            checkAll(Arb.pattern("[a-z][a-z0-9-]{1,38}-").filter { it.length <= 40 }) { raw ->
                invalid(raw).shouldNotBeBlank()
            }
        }

        test("edge cases: 3 and 40 characters are valid, 2 and 41 characters and blank are invalid") {
            valid("abc").value shouldBe "abc"
            valid("a" + "b".repeat(39)).value.length shouldBe 40
            invalid("ab").shouldNotBeBlank()
            invalid("a" + "b".repeat(40)).shouldNotBeBlank()
            invalid("").shouldNotBeBlank()
            invalid("   ").shouldNotBeBlank()
        }

        test("the reason of an invalid name names the rejected input") {
            invalid("Bad_Name") shouldContain "Bad_Name"
        }

        test("two names built from the same input are equal and print their value") {
            val first = valid("catalog")
            val second = valid("catalog")
            first shouldBe second
            first.hashCode() shouldBe second.hashCode()
            first shouldNotBe valid("orders")
            first.toString() shouldContain "catalog"
        }
    })

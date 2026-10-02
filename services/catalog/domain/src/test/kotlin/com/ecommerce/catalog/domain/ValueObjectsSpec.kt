package com.ecommerce.catalog.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class ValueObjectsSpec :
    FunSpec({
        test("a SKU is 3 to 32 characters of A-Z, 0-9 and -") {
            checkAll(Arb.string(3..32, Codepoint.az()).map { it.uppercase() }) { raw ->
                Sku.of(raw).value().value shouldBe raw
            }
            Sku.of("ESP-MACH-01").value().value shouldBe "ESP-MACH-01"
            listOf("AB", "A".repeat(33), "ab-1", "AB 1", "", "ÄBC").forEach { raw ->
                Sku.of(raw).error() shouldBe FieldIssue("sku", "must be 3 to 32 characters of A-Z, 0-9 and -")
            }
            Sku.of("ABC").value().value shouldBe "ABC"
            Sku.of("A".repeat(32)).value().value shouldBe "A".repeat(32)
        }

        test("a generated SKU is valid and derived from the product id") {
            checkAll(DomainArbs.productId) { id ->
                val sku = Sku.generatedFor(id).value
                sku shouldMatch Regex("P-[0-9A-F]{10}")
                Sku.of(sku).value().value shouldBe sku
                val text = id.value.toString().uppercase()
                sku.drop(2) shouldBe text.take(8) + text.substring(9, 11)
            }
        }

        test("product and category names are trimmed, 1 to 120 characters, without control characters") {
            checkAll(Arb.string(1..120).map { it.filter { c -> !c.isISOControl() && !c.isWhitespace() } }) { raw ->
                if (raw.isNotEmpty()) {
                    ProductName.of(" $raw ").value().value shouldBe raw
                    CategoryName.of(raw).value().value shouldBe raw
                }
            }
            ProductName.of("   ").error() shouldBe FieldIssue("name", "must not be blank")
            ProductName.of("x".repeat(121)).error() shouldBe FieldIssue("name", "must be at most 120 characters")
            ProductName
                .of("x".repeat(120))
                .value()
                .value.length shouldBe 120
            ProductName.of("a").value().value shouldBe "a"
            ProductName.of("a\u0007b").error() shouldBe FieldIssue("name", "must not contain control characters")
            ProductName.of("a\nb").error().field shouldBe "name"
            CategoryName.of("").error() shouldBe FieldIssue("name", "must not be blank")
            CategoryName.of("y".repeat(121)).error().reason shouldBe "must be at most 120 characters"
        }

        test("a description is optional, up to its limit, and may span lines") {
            Description.of(null).value() shouldBe null
            Description.of("  ").value() shouldBe null
            Description.of(" Two\nlines\tand tab ").value()?.value shouldBe "Two\nlines\tand tab"
            Description
                .of("x".repeat(Description.PRODUCT_MAX))
                .value()
                ?.value
                ?.length shouldBe Description.PRODUCT_MAX
            Description.of("x".repeat(Description.PRODUCT_MAX + 1)).error() shouldBe
                FieldIssue("description", "must be at most 4000 characters")
            Description.of("x".repeat(Description.CATEGORY_MAX + 1), Description.CATEGORY_MAX).error().reason shouldBe
                "must be at most 1000 characters"
            Description.of("bell\u0007").error() shouldBe
                FieldIssue("description", "must not contain control characters")
        }

        test("an image reference is an https URL or a storage key of at most 2048 characters") {
            val url = "https://cdn.example.test/shoes.jpg"
            ImageRef.of(" $url ").value().value shouldBe url
            ImageRef.of("products/shoes-01.jpg").value().value shouldBe "products/shoes-01.jpg"
            ImageRef.of("").error() shouldBe FieldIssue("url", "must not be blank")
            ImageRef.of("http://cdn.example.test/a.jpg").error() shouldBe
                FieldIssue("url", "must be an https URL or a storage key")
            ImageRef.of("https://").error().field shouldBe "url"
            ImageRef.of("a b").error().field shouldBe "url"
            val longest = "https://cdn.example.test/" + "a".repeat(ImageRef.MAX - "https://cdn.example.test/".length)
            ImageRef
                .of(longest)
                .value()
                .value.length shouldBe ImageRef.MAX
            ImageRef.of(longest + "a").error() shouldBe FieldIssue("url", "must be at most 2048 characters")
            ImageRef.altText(null).value() shouldBe null
            ImageRef.altText(" ").value() shouldBe null
            ImageRef.altText(" Side view ").value() shouldBe "Side view"
            ImageRef.altText("a".repeat(ImageRef.ALT_TEXT_MAX)).value()?.length shouldBe ImageRef.ALT_TEXT_MAX
            ImageRef.altText("a".repeat(ImageRef.ALT_TEXT_MAX + 1)).error().field shouldBe "altText"
        }

        test("a stock level is 0 to one million units") {
            checkAll(Arb.int(0..StockLevel.MAX)) { units -> StockLevel.of(units).value().value shouldBe units }
            StockLevel.of(-1).error() shouldBe FieldIssue("initialStock", "must not be negative")
            StockLevel.of(StockLevel.MAX + 1).error() shouldBe FieldIssue("initialStock", "must be at most 1000000")
            StockLevel.of(0, "stock").value().value shouldBe 0
            StockLevel.of(-1, "stock").error().field shouldBe "stock"
        }

        test("an adjustment reason is mandatory, 3 to 200 characters") {
            StockAdjustmentReason.of(" Delivery received ").value().value shouldBe "Delivery received"
            StockAdjustmentReason.of(null).error() shouldBe FieldIssue("reason", "must be at least 3 characters")
            StockAdjustmentReason.of("ab").error().reason shouldBe "must be at least 3 characters"
            StockAdjustmentReason.of("abc").value().value shouldBe "abc"
            StockAdjustmentReason
                .of("r".repeat(200))
                .value()
                .value.length shouldBe 200
            StockAdjustmentReason.of("r".repeat(201)).error().reason shouldBe "must be at most 200 characters"
        }

        test("a quantity is 1 to 99") {
            checkAll(Arb.int()) { units ->
                val result = Quantity.of(units)
                if (units in 1..99) result.value().value shouldBe units else result.error().field shouldBe "quantity"
            }
            Quantity.of(0).error() shouldBe FieldIssue("quantity", "must be 1 to 99")
            Quantity.of(100).error() shouldBe FieldIssue("quantity", "must be 1 to 99")
            Quantity.of(1).value().value shouldBe 1
            Quantity.of(99).value().value shouldBe 99
        }

        test("a price is a positive amount of a three-letter currency") {
            checkAll(Arb.long(1L..Long.MAX_VALUE)) { amount ->
                Money.price(amount, BRL).value() shouldBe Money(amount, BRL)
            }
            checkAll(Arb.long(Long.MIN_VALUE..0L)) { amount ->
                Money.price(amount, BRL).error() shouldBe FieldIssue("price.amountMinor", "must be greater than 0")
            }
            Money.price(1, "brl").error() shouldBe FieldIssue("price.currency", "must be an ISO-4217 code")
            Money(0, BRL).amountMinor shouldBe 0
            shouldThrow<IllegalArgumentException> { Money(-1, BRL) }
            shouldThrow<IllegalArgumentException> { Money(1, "BR") }
        }

        test("a page is 0-based with 1 to 100 items") {
            checkAll(Arb.int(0..1000), Arb.int(1..100)) { page, size ->
                val request = PageRequest.of(page, size).value()
                request.page shouldBe page
                request.size shouldBe size
                request.offset shouldBe page.toLong() * size
            }
            PageRequest.of().value() shouldBe PageRequest.of(0, PageRequest.DEFAULT_SIZE).value()
            PageRequest.of(-1, 1).error() shouldBe FieldIssue("page", "must be at least 0")
            PageRequest.of(0, 0).error() shouldBe FieldIssue("size", "must be at least 1")
            PageRequest.of(0, 101).error() shouldBe FieldIssue("size", "must be at most 100")
            PageRequest.of(Int.MAX_VALUE, 100).value().offset shouldBe Int.MAX_VALUE.toLong() * 100
            Page(listOf(1, 2), 0, 2, 5).map { it * 10 } shouldBe Page(listOf(10, 20), 0, 2, 5)
        }

        test("a search term ranks the whole name, a prefix, a name match and a description match") {
            val term = SearchTerm.of("  LanTern ").value()
            term.value shouldBe "lantern"
            term.rank("Lantern", null) shouldBe SearchTerm.EXACT
            term.rank("lantern lamp", "x") shouldBe SearchTerm.PREFIX
            term.rank("Old LANTERN", null) shouldBe SearchTerm.IN_NAME
            term.rank("Desk", "Goes well with a lantern.") shouldBe SearchTerm.IN_DESCRIPTION
            term.rank("Desk", "Plain.") shouldBe null
            term.rank("Desk", null) shouldBe null
            val ranks = listOf(SearchTerm.EXACT, SearchTerm.PREFIX, SearchTerm.IN_NAME, SearchTerm.IN_DESCRIPTION)
            ranks shouldContainExactly listOf(0, 1, 2, 3)
            SearchTerm.of(" ").error() shouldBe FieldIssue("q", "must not be blank")
            SearchTerm.of("q".repeat(101)).error() shouldBe FieldIssue("q", "must be at most 100 characters")
            SearchTerm
                .of("q".repeat(100))
                .value()
                .value.length shouldBe 100
        }
    })

package com.ecommerce.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.util.UUID

private fun addressId(): AddressId = AddressId(UUID.randomUUID())

class AddressSpec :
    FunSpec({
        test("a postal address is trimmed, keeps optional lines as null when blank and masks itself") {
            val address =
                PostalAddress
                    .validate(fields(recipientName = " Ada ", line2 = "  ", region = null, countryCode = "GB"))
                    .value()

            address.recipientName shouldBe "Ada"
            address.line1 shouldBe "12 Analytical Street"
            address.line2.shouldBeNull()
            address.city shouldBe "London"
            address.region.shouldBeNull()
            address.postalCode shouldBe "N1 9GU"
            address.countryCode shouldBe "GB"
            address.toString() shouldBe "PostalAddress(countryCode=GB)"
            fields().toString() shouldNotContain "Ada"
            PostalAddress.validate(fields()).value().line2 shouldBe "Flat 2"
            PostalAddress.validate(fields()).value().region shouldBe "England"
        }

        test("every broken postal rule is reported at once") {
            val errors =
                PostalAddress
                    .validate(PostalAddress.Fields(null, " ", "x".repeat(121), "", "y".repeat(81), null, "gb"))
                    .error()

            errors shouldContainExactly
                listOf(
                    FieldError("recipientName", "must not be blank"),
                    FieldError("line1", "must not be blank"),
                    FieldError("line2", "must be at most 120 characters"),
                    FieldError("city", "must not be blank"),
                    FieldError("region", "must be at most 80 characters"),
                    FieldError("postalCode", "must not be blank"),
                    FieldError("countryCode", "must be an ISO-3166 alpha-2 country code"),
                )
        }

        test("postal field lengths are enforced at their limits") {
            val limits =
                listOf(
                    Triple(
                        "recipientName",
                        PostalAddress.RECIPIENT_NAME_MAX,
                    ) { v: String -> fields(recipientName = v) },
                    Triple("line1", PostalAddress.LINE_MAX) { v: String -> fields(line1 = v) },
                    Triple("line2", PostalAddress.LINE_MAX) { v: String -> fields(line2 = v) },
                    Triple("city", PostalAddress.CITY_MAX) { v: String -> fields(city = v) },
                    Triple("region", PostalAddress.REGION_MAX) { v: String -> fields(region = v) },
                    Triple("postalCode", PostalAddress.POSTAL_CODE_MAX) { v: String -> fields(postalCode = v) },
                )
            limits.forEach { (field, max, build) ->
                PostalAddress.validate(build("x".repeat(max))).isRight() shouldBe true
                PostalAddress.validate(build("x".repeat(max + 1))).error() shouldContainExactly
                    listOf(FieldError(field, "must be at most $max characters"))
            }
            PostalAddress
                .validate(fields(countryCode = "XX"))
                .error()
                .single()
                .field shouldBe "countryCode"
            PostalAddress.validate(fields(countryCode = " PT ")).value().countryCode shouldBe "PT"
        }

        test("an address draft takes an optional label of at most 50 characters") {
            AddressDraft
                .of("  ", fields(), true)
                .value()
                .label
                .shouldBeNull()
            AddressDraft.of(null, fields(), false).value().isDefault shouldBe false
            AddressDraft.of(" Home ", fields(), true).value().label shouldBe "Home"
            AddressDraft
                .of("x".repeat(AddressDraft.LABEL_MAX), fields(), true)
                .value()
                .label
                ?.length shouldBe 50
            AddressDraft.of("x".repeat(AddressDraft.LABEL_MAX + 1), fields(), true).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("label", "must be at most 50 characters")))
            AddressDraft.of("x".repeat(AddressDraft.LABEL_MAX + 1), fields(city = null), true).error() shouldBe
                IdentityError.Invalid(
                    listOf(
                        FieldError("label", "must be at most 50 characters"),
                        FieldError("city", "must not be blank"),
                    ),
                )
            AddressDraft.of("Home", fields(city = null), true).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("city", "must not be blank")))
            draft().toString() shouldNotContain "Home"
        }

        test("a book holds at most 10 addresses") {
            checkAll(Arb.int(0..12)) { count ->
                var book = AddressBook.empty(accountId())
                var refused = 0
                repeat(count) {
                    book.add(addressId(), draft()).fold({ refused++ }, { added -> book = added })
                }
                book.addresses.size shouldBe minOf(count, AddressBook.MAX_ADDRESSES)
                refused shouldBe maxOf(0, count - AddressBook.MAX_ADDRESSES)
            }
            val full =
                AddressBook(
                    accountId(),
                    List(AddressBook.MAX_ADDRESSES) { Address(addressId(), null, draft().postal, false) },
                )
            full.add(addressId(), draft()).error() shouldBe IdentityError.TooManyAddresses
        }

        test("a new default takes the flag from the previous one and a non-default keeps it") {
            val first = addressId()
            val second = addressId()
            val third = addressId()
            val book =
                AddressBook
                    .empty(accountId())
                    .add(first, draft(isDefault = true))
                    .value()
                    .add(second, draft(isDefault = false))
                    .value()

            book.addresses.map { it.isDefault } shouldBe listOf(true, false)
            book
                .add(third, draft(isDefault = true))
                .value()
                .addresses
                .map { it.isDefault } shouldBe
                listOf(false, false, true)
            book
                .replace(second, draft(isDefault = true))
                .value()
                .addresses
                .map { it.isDefault } shouldBe
                listOf(false, true)
            book.replace(first, draft(isDefault = false, label = "Work")).value().addresses.map {
                it.isDefault to
                    it.label
            } shouldBe
                listOf(false to "Work", false to "Home")
            shouldThrow<IllegalArgumentException> {
                AddressBook(
                    accountId(),
                    listOf(Address(first, null, draft().postal, true), Address(second, null, draft().postal, true)),
                )
            }
        }

        test("replacing and removing need an address of the book; removing the default promotes none") {
            val kept = addressId()
            val removed = addressId()
            val book =
                AddressBook
                    .empty(accountId())
                    .add(kept, draft())
                    .value()
                    .add(removed, draft(isDefault = true))
                    .value()

            val after = book.remove(removed).value()
            after.addresses.map { it.id } shouldBe listOf(kept)
            after.addresses.single().isDefault shouldBe false
            book.find(kept)?.id shouldBe kept
            book.find(addressId()).shouldBeNull()
            book.remove(addressId()).error() shouldBe IdentityError.AddressNotFound
            book.replace(addressId(), draft()).error() shouldBe IdentityError.AddressNotFound
            book
                .replace(kept, draft(label = "New"))
                .value()
                .find(kept)
                ?.label shouldBe "New"
            book.cleared().addresses.shouldBeEmpty()
            book.cleared().accountId shouldBe book.accountId
        }

        test("a page returns at most limit addresses after offset") {
            val ids = List(AddressBook.MAX_ADDRESSES) { addressId() }
            val book = AddressBook(accountId(), ids.map { Address(it, null, draft().postal, false) })
            checkAll(Arb.long(0L..15L), Arb.int(1..12)) { offset, limit ->
                book.page(offset, limit).map { it.id } shouldBe ids.drop(offset.toInt()).take(limit)
            }
            book.page(Long.MAX_VALUE, 5).shouldBeEmpty()
        }
    })

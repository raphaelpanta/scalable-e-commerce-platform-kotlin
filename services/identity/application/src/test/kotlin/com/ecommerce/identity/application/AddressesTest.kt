package com.ecommerce.identity.application

import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AddressBook
import com.ecommerce.identity.domain.AddressId
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.PostalAddress
import com.ecommerce.identity.domain.Pseudonym
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

fun input(
    city: String? = "Lisboa",
    isDefault: Boolean = true,
    label: String? = "Home",
): AddressInput =
    AddressInput(
        label,
        PostalAddress.Fields("Ada Lovelace", "Rua das Flores 12", null, city, null, "1000-001", "PT"),
        isDefault,
    )

class AddressesTest :
    FunSpec({
        test("an added address gets a new id, is listed and keeps at most one default") {
            val harness = Harness()
            val ada = harness.shopper()
            val add = AddAddress(harness.store)

            val lisboa = add(ada.id, input("Lisboa")).value()
            val porto = add(ada.id, input("Porto")).value()

            lisboa.id shouldBe AddressId(UUID(0L, 1L))
            lisboa.label shouldBe "Home"
            lisboa.postal.city shouldBe "Lisboa"
            porto.isDefault shouldBe true
            val page = ListAddresses(harness.store)(ada.id, 0, 20).value()
            page.totalItems shouldBe 2
            page.items.map { it.postal.city to it.isDefault } shouldBe listOf("Lisboa" to false, "Porto" to true)
            ListAddresses(harness.store)(ada.id, 1, 20).value().items.map { it.id } shouldBe listOf(porto.id)
            ListAddresses(harness.store)(ada.id, 0, 1).value().totalItems shouldBe 2
            input().toString() shouldNotContain "Lisboa"
        }

        test("invalid inputs, an eleventh address and unknown accounts are refused") {
            val harness = Harness()
            val ada = harness.shopper()
            val add = AddAddress(harness.store)

            add(ada.id, input(city = " ")).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("city", "must not be blank")))
            repeat(AddressBook.MAX_ADDRESSES) { add(ada.id, input("City $it")).value() }
            add(ada.id, input()).error() shouldBe IdentityError.TooManyAddresses
            add(AccountId(UUID.randomUUID()), input()).error() shouldBe IdentityError.AccountNotFound
            ListAddresses(harness.store)(AccountId(UUID.randomUUID()), 0, 20).error() shouldBe
                IdentityError.AccountNotFound
            harness.addresses.books[ada.id]
                ?.addresses
                ?.size shouldBe AddressBook.MAX_ADDRESSES
        }

        test("an address is replaced or removed by its owner only") {
            val harness = Harness()
            val ada = harness.shopper()
            val grace = harness.shopper("grace@example.test")
            val porto = AddAddress(harness.store)(ada.id, input("Porto")).value()
            val replace = ReplaceAddress(harness.store)
            val remove = RemoveAddress(harness.store)

            replace(ada.id, porto.id, input("Braga", isDefault = false, label = null)).value().postal.city shouldBe
                "Braga"
            harness.addresses.books[ada.id]
                ?.find(porto.id)
                ?.label shouldBe null
            replace(grace.id, porto.id, input()).error() shouldBe IdentityError.AddressNotFound
            replace(ada.id, AddressId(UUID.randomUUID()), input()).error() shouldBe IdentityError.AddressNotFound
            replace(ada.id, porto.id, input(city = null)).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("city", "must not be blank")))
            replace(AccountId(UUID.randomUUID()), porto.id, input()).error() shouldBe IdentityError.AccountNotFound
            remove(grace.id, porto.id).error() shouldBe IdentityError.AddressNotFound
            remove(AccountId(UUID.randomUUID()), porto.id).error() shouldBe IdentityError.AccountNotFound

            remove(ada.id, porto.id).value()
            harness.addresses.books[ada.id]
                ?.addresses
                ?.shouldBeEmpty()
            remove(ada.id, porto.id).error() shouldBe IdentityError.AddressNotFound
        }

        test("the internal lookup returns the postal fields of an address of a live account only") {
            val harness = Harness()
            val ada = harness.shopper()
            val grace = harness.shopper("grace@example.test")
            val home = AddAddress(harness.store)(ada.id, input("Lisboa")).value()
            val lookup = GetAccountAddress(harness.store)

            lookup(ada.id, home.id).value() shouldBe home.postal
            lookup(grace.id, home.id).error() shouldBe IdentityError.AddressNotFound
            lookup(ada.id, AddressId(UUID.randomUUID())).error() shouldBe IdentityError.AddressNotFound
            lookup(AccountId(UUID.randomUUID()), home.id).error() shouldBe IdentityError.AddressNotFound
            harness.accounts.store(ada.anonymise(Pseudonym.of(ada.id), NOW).value())
            lookup(ada.id, home.id).error() shouldBe IdentityError.AddressNotFound
        }
    })

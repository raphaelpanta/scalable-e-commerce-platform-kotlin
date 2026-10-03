package com.ecommerce.identity.application

import com.ecommerce.identity.application.IdentityArbs.PROPERTIES
import com.ecommerce.identity.domain.AccountDeleted
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.AddressBook
import com.ecommerce.identity.domain.AddressId
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.PhoneNumber
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.Pseudonym
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.VerificationCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.Duration
import java.util.UUID

private const val MAX_SESSIONS = 3

/**
 * T122 / FR-007: deleting any shopper, whatever it holds (addresses, a verified phone, a pending code, sessions,
 * pending tokens), anonymises the account in place and removes or revokes every piece of personal data, announcing
 * only the pseudonym; operators can never delete themselves.
 */
class DeletionPropertySpec :
    FunSpec({
        test("deletion anonymises the account and removes or revokes everything personal") {
            checkAll(
                PROPERTIES,
                IdentityArbs.email,
                Arb.list(IdentityArbs.addressDraft, 0..AddressBook.MAX_ADDRESSES),
                Arb.int(0..MAX_SESSIONS),
                Arb.boolean(),
                IdentityArbs.phone,
            ) { address, drafts, sessions, verifiedPhone, rawPhone ->
                val harness = Harness()
                val ada = harness.shopper(address)
                val phone = PhoneNumber.of(rawPhone).value()
                harness.addresses.books[ada.id] =
                    drafts.fold(AddressBook.empty(ada.id)) { book, draft ->
                        book.add(AddressId(UUID.randomUUID()), draft).value()
                    }
                if (verifiedPhone) {
                    harness.preferences.preferences[ada.id] =
                        NotificationPreference
                            .default(ada.id)
                            .withVerifiedPhone(phone)
                            .choose(setOf(NotificationChannel.EMAIL, NotificationChannel.SMS))
                            .value()
                }
                harness.preferences.verifications[ada.id] =
                    PhoneVerification.issue(ada.id, phone, VerificationCode.of("123456").value(), NOW)
                repeat(sessions) { SignIn(harness.store)(Credentials(address, PASSWORD, "s$it")).value() }
                RequestPasswordReset(harness.store)(address).value()
                harness.clock.advance(Duration.ofMinutes(1))
                val now = harness.clock.instant

                DeleteAccount(harness.store)(ada.id).value()

                val pseudonym = Pseudonym.of(ada.id)
                val deleted = harness.accounts[ada.id]
                deleted shouldBe
                    ada.copy(
                        email = deleted.email,
                        passwordHash = null,
                        status = AccountStatus.DELETED,
                        displayName = null,
                        deletedAt = now,
                        pseudonym = pseudonym,
                        version = ada.version + 1,
                    )
                deleted.email.value shouldBe pseudonym.placeholderEmail
                harness.addresses.books[ada.id]
                    ?.addresses
                    .orEmpty()
                    .shouldBeEmpty()
                harness.preferences.preferences[ada.id] shouldBe NotificationPreference.default(ada.id)
                harness.preferences.verifications[ada.id].shouldBeNull()
                harness.sessions.of(ada.id).forEach { it.revokedAt shouldBe now }
                harness.tokens.tokens.values
                    .filter { it.accountId == ada.id }
                    .forEach { it.usedAt shouldBe now }
                harness.events.deleted shouldBe listOf(AccountDeleted(ada.id, pseudonym, now))
                harness.events.deleted
                    .single()
                    .toString() shouldNotContain address

                DeleteAccount(harness.store)(ada.id).error() shouldBe IdentityError.AccountNotFound
                SignIn(harness.store)(Credentials(address, PASSWORD, "x")).error() shouldBe
                    IdentityError.InvalidCredentials
                GetProfile(harness.store)(ada.id).error() shouldBe IdentityError.AccountNotFound
                val contact = GetAccountContact(harness.store)(ada.id).value()
                contact.anonymised shouldBe true
                contact.phone.shouldBeNull()
                contact.channels.shouldBeEmpty()
                harness.events.deleted.size shouldBe 1
            }
        }

        test("an operator can never delete itself, and the refusal changes nothing") {
            checkAll(PROPERTIES, IdentityArbs.email, Arb.list(IdentityArbs.addressDraft, 0..2)) { address, drafts ->
                val harness = Harness()
                val operator = harness.shopper(address).copy(roles = setOf(Role.SHOPPER, Role.OPERATOR))
                harness.accounts.store(operator)
                val book =
                    drafts.fold(AddressBook.empty(operator.id)) { current, draft ->
                        current.add(AddressId(UUID.randomUUID()), draft).value()
                    }
                harness.addresses.books[operator.id] = book

                DeleteAccount(harness.store)(operator.id).error() shouldBe IdentityError.OperatorCannotSelfDelete

                harness.accounts[operator.id] shouldBe operator
                harness.addresses.books[operator.id] shouldBe book
                harness.events.all.shouldBeEmpty()
                harness.transactions.rollbacks shouldBe 1
            }
        }

        test("the email of a deleted account can be registered again as a new account") {
            checkAll(PROPERTIES, IdentityArbs.email, IdentityArbs.password) { address, password ->
                val harness = Harness()
                val ada = harness.shopper(address)
                DeleteAccount(harness.store)(ada.id).value()

                RegisterAccount(harness.store)(Registration(address, password, null)).value()

                val fresh =
                    harness.accounts.accounts.values
                        .single { it.id != ada.id }
                fresh.email.value shouldBe address
                fresh.status shouldBe AccountStatus.UNVERIFIED
                harness.events.registered
                    .single()
                    .recipient.accountId shouldBe fresh.id
            }
        }
    })

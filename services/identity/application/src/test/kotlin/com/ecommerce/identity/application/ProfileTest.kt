package com.ecommerce.identity.application

import com.ecommerce.identity.domain.AccountContact
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.Pseudonym
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.TokenPurpose
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID

class ProfileTest :
    FunSpec({
        test("the profile is the caller's live account; unknown and deleted accounts are not found") {
            val harness = Harness()
            val ada = harness.shopper()

            GetProfile(harness.store)(ada.id).value() shouldBe ada
            GetProfile(harness.store)(AccountId(UUID.randomUUID())).error() shouldBe IdentityError.AccountNotFound
            harness.accounts.store(ada.anonymise(Pseudonym.of(ada.id), NOW).value())
            GetProfile(harness.store)(ada.id).error() shouldBe IdentityError.AccountNotFound
        }

        test("the display name can change or be cleared and is validated") {
            val harness = Harness()
            val ada = harness.shopper()
            val update = UpdateProfile(harness.store)

            update(ada.id, " Ada M. Lovelace ").value().displayName?.value shouldBe "Ada M. Lovelace"
            harness.accounts[ada.id].displayName?.value shouldBe "Ada M. Lovelace"
            update(ada.id, null).value().displayName.shouldBeNull()
            update(ada.id, "x".repeat(101)).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("displayName", "must be at most 100 characters")))
            update(AccountId(UUID.randomUUID()), "Grace").error() shouldBe IdentityError.AccountNotFound
            harness.accounts.losingUpdates = 1
            update(ada.id, "Grace").error() shouldBe IdentityError.ConcurrentUpdate
        }

        test(
            "deleting anonymises the account, removes addresses and phone, revokes sessions and tokens, announces it",
        ) {
            val harness = Harness()
            val ada = harness.shopper()
            SignIn(harness.store)(Credentials(ADA, PASSWORD, "s")).value()
            RequestPasswordReset(harness.store)(ADA).value()
            AddAddress(harness.store)(ada.id, input()).value()
            harness.preferences.save(NotificationPreference.default(ada.id).withVerifiedPhone(phone()))
            harness.preferences.saveVerification(
                PhoneVerification.issue(ada.id, phone(), FakeSecrets().verificationCode(), NOW),
            )
            harness.clock.advance(Duration.ofHours(1))

            DeleteAccount(harness.store)(ada.id).value()

            val pseudonym = Pseudonym.of(ada.id)
            val deleted = harness.accounts[ada.id]
            deleted.status shouldBe AccountStatus.DELETED
            deleted.email.value shouldBe pseudonym.placeholderEmail
            deleted.passwordHash.shouldBeNull()
            deleted.pseudonym shouldBe pseudonym
            harness.addresses.books[ada.id]
                ?.addresses
                ?.shouldBeEmpty()
            harness.preferences.preferences[ada.id] shouldBe NotificationPreference.default(ada.id)
            harness.preferences.verifications[ada.id].shouldBeNull()
            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt shouldBe NOW.plus(Duration.ofHours(1))
            harness.tokens
                .of(ada.id, TokenPurpose.PASSWORD_RESET)
                .single()
                .usedAt shouldBe
                NOW.plus(Duration.ofHours(1))
            harness.events.deleted
                .single()
                .pseudonym shouldBe pseudonym
            harness.events.deleted
                .single()
                .accountId shouldBe ada.id
            harness.events.deleted
                .single()
                .deletedAt shouldBe NOW.plus(Duration.ofHours(1))
            DeleteAccount(harness.store)(ada.id).error() shouldBe IdentityError.AccountNotFound
            SignIn(harness.store)(Credentials(ADA, PASSWORD, "t")).error() shouldBe IdentityError.InvalidCredentials
        }

        test("operators cannot delete themselves and nothing changes") {
            val harness = Harness()
            val operator = harness.shopper()
            harness.accounts.store(operator.copy(roles = setOf(Role.SHOPPER, Role.OPERATOR)))
            AddAddress(harness.store)(operator.id, input()).value()

            DeleteAccount(harness.store)(operator.id).error() shouldBe IdentityError.OperatorCannotSelfDelete

            harness.accounts[operator.id].status shouldBe AccountStatus.ACTIVE
            harness.addresses.books[operator.id]
                ?.addresses
                ?.size shouldBe 1
            harness.events.deleted.shouldBeEmpty()
            harness.transactions.rollbacks shouldBe 1
        }

        test("the contact of a live account lists its permitted channels; a deleted account is anonymised") {
            val harness = Harness()
            val ada = harness.shopper()
            val contact = GetAccountContact(harness.store)

            contact(ada.id).value() shouldBe
                AccountContact(ada.id, ada.email, null, false, listOf(NotificationChannel.EMAIL), false)
            val sms =
                NotificationPreference
                    .default(
                        ada.id,
                    ).withVerifiedPhone(phone())
                    .choose(NotificationChannel.entries.toSet())
                    .value()
            harness.preferences.save(sms)
            contact(ada.id).value().channels shouldBe listOf(NotificationChannel.EMAIL, NotificationChannel.SMS)

            DeleteAccount(harness.store)(ada.id).value()
            val anonymised = contact(ada.id).value()
            anonymised.anonymised shouldBe true
            anonymised.channels.shouldBeEmpty()
            anonymised.email.value shouldBe Pseudonym.of(ada.id).placeholderEmail
            contact(AccountId(UUID.randomUUID())).error() shouldBe IdentityError.AccountNotFound
        }
    })

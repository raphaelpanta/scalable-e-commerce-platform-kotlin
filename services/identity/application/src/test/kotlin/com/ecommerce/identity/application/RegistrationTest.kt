package com.ecommerce.identity.application

import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.Email
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.TokenPurpose
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

class RegistrationTest :
    FunSpec({
        test("registration creates an unverified shopper, its preferences and a 24 h token, and announces it") {
            val harness = Harness()

            RegisterAccount(harness.store)(Registration(" Ada@Example.test ", PASSWORD, " Ada ")).value()

            val account =
                harness.accounts.accounts.values
                    .single()
            account.email.value shouldBe ADA
            account.status shouldBe AccountStatus.UNVERIFIED
            account.roles shouldBe setOf(Role.SHOPPER)
            account.displayName?.value shouldBe "Ada"
            account.passwordHash?.value shouldBe "hash:$PASSWORD"
            account.createdAt shouldBe NOW
            harness.preferences.preferences[account.id] shouldBe NotificationPreference.default(account.id)
            val token = harness.tokens.of(account.id, TokenPurpose.EMAIL_VERIFICATION).single()
            token.hash shouldBe FakeSecrets.token(0).hash()
            token.expiresAt shouldBe NOW.plus(Duration.ofHours(24))
            val event = harness.events.registered.single()
            event.verificationToken shouldBe FakeSecrets.token(0)
            event.tokenExpiresAt shouldBe token.expiresAt
            event.recipient.accountId shouldBe account.id
            event.recipient.email shouldBe account.email
            event.recipient.phone.shouldBeNull()
            event.recipient.preferredChannels shouldBe listOf(NotificationChannel.EMAIL)
            harness.transactions.transactions shouldBe 1
        }

        test("an email that is already registered creates nothing and answers the same, after hashing") {
            val harness = Harness()
            val existing = harness.shopper()

            RegisterAccount(harness.store)(Registration(ADA.uppercase(), "Another-passphrase1", null)).value()

            harness.accounts.accounts.values
                .single() shouldBe existing
            harness.events.all.shouldBeEmpty()
            harness.tokens.tokens.values
                .shouldBeEmpty()
            harness.hasher.hashed shouldBe 1
            harness.transactions.transactions shouldBe 0
        }

        test("a registration that loses the race for its email creates nothing more") {
            val harness = Harness()
            val racing =
                object : AccountRepository by harness.accounts {
                    override suspend fun findByEmail(email: Email): Account? = null
                }
            harness.shopper()
            val store = harness.store.copy(accounts = racing)

            RegisterAccount(store)(Registration(ADA, PASSWORD, null)).value()

            harness.accounts.accounts.size shouldBe 1
            harness.events.all.shouldBeEmpty()
            harness.preferences.preferences.values
                .shouldBeEmpty()
        }

        test("every broken rule of the input is reported at once and nothing is stored") {
            val harness = Harness()

            val error =
                RegisterAccount(harness.store)(Registration("not-an-email", "short", "x".repeat(101))).error()

            error shouldBe
                IdentityError.Invalid(
                    listOf(
                        FieldError("email", "must contain exactly one @"),
                        FieldError("password", "must be at least 12 characters"),
                        FieldError("displayName", "must be at most 100 characters"),
                    ),
                )
            RegisterAccount(harness.store)(Registration(ADA, ADA, null)).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("password", "must not be the email address")))
            harness.accounts.accounts.values
                .shouldBeEmpty()
            harness.hasher.hashed shouldBe 0
            Registration(ADA, PASSWORD, null).toString() shouldNotContain PASSWORD
        }

        test("the emailed token verifies the account once and announces it") {
            val harness = Harness()
            RegisterAccount(harness.store)(Registration(ADA, PASSWORD, null)).value()
            val id =
                harness.accounts.accounts.keys
                    .single()
            harness.clock.advance(Duration.ofHours(1))

            VerifyEmail(harness.store)(FakeSecrets.token(0).value).value()

            val verified = harness.accounts[id]
            verified.status shouldBe AccountStatus.ACTIVE
            verified.verifiedAt shouldBe NOW.plus(Duration.ofHours(1))
            harness.tokens
                .of(id, TokenPurpose.EMAIL_VERIFICATION)
                .single()
                .usedAt shouldBe verified.verifiedAt
            val event = harness.events.verified.single()
            event.verifiedAt shouldBe verified.verifiedAt
            event.recipient.email shouldBe verified.email
            VerifyEmail(harness.store)(FakeSecrets.token(0).value).error() shouldBe IdentityError.InvalidToken
            harness.events.verified shouldHaveSize 1
        }

        test("malformed, unknown, expired and concurrently spent tokens are refused alike") {
            val harness = Harness()
            RegisterAccount(harness.store)(Registration(ADA, PASSWORD, null)).value()
            val verify = VerifyEmail(harness.store)

            verify("not-a-token").error() shouldBe IdentityError.InvalidToken
            verify(FakeSecrets.token(9).value).error() shouldBe IdentityError.InvalidToken
            harness.tokens.losingMarks = 1
            verify(FakeSecrets.token(0).value).error() shouldBe IdentityError.InvalidToken
            harness.clock.advance(Duration.ofHours(24))
            verify(FakeSecrets.token(0).value).error() shouldBe IdentityError.InvalidToken
            harness.accounts.accounts.values
                .single()
                .status shouldBe AccountStatus.UNVERIFIED
            harness.events.verified.shouldBeEmpty()
            harness.transactions.rollbacks shouldBe 3
        }

        test("a token of a vanished or deleted account, or of an account already active, verifies nothing new") {
            val harness = Harness()
            RegisterAccount(harness.store)(Registration(ADA, PASSWORD, null)).value()
            val id =
                harness.accounts.accounts.keys
                    .single()
            val account = harness.accounts[id]
            harness.accounts.accounts.remove(id)

            VerifyEmail(harness.store)(FakeSecrets.token(0).value).error() shouldBe IdentityError.InvalidToken

            harness.accounts.store(account.verify(NOW).value())
            VerifyEmail(harness.store)(FakeSecrets.token(0).value).value()
            harness.events.verified.shouldBeEmpty()
        }

        test("a verification that loses the race for the account rolls back") {
            val harness = Harness()
            RegisterAccount(harness.store)(Registration(ADA, PASSWORD, null)).value()
            harness.accounts.losingUpdates = 1

            VerifyEmail(harness.store)(FakeSecrets.token(0).value).error() shouldBe IdentityError.ConcurrentUpdate

            harness.accounts.accounts.values
                .single()
                .status shouldBe AccountStatus.UNVERIFIED
            harness.tokens.tokens.values
                .single()
                .usedAt
                .shouldBeNull()
            harness.events.verified.shouldBeEmpty()
            harness.accounts.accounts.values
                .single()
                .id
                .shouldNotBeNull()
        }
    })

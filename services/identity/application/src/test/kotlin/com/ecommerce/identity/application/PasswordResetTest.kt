package com.ecommerce.identity.application

import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.domain.Pseudonym
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.TokenPurpose
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

private const val NEW_PASSWORD = "Brand-new-passphrase!"

class PasswordResetTest :
    FunSpec({
        test("a reset request issues a one-hour token, invalidates the previous one and announces it") {
            val harness = Harness()
            val ada = harness.shopper()
            val request = RequestPasswordReset(harness.store)

            request(" ADA@example.test ").value()
            harness.clock.advance(Duration.ofMinutes(1))
            request(ADA).value()

            val tokens = harness.tokens.of(ada.id, TokenPurpose.PASSWORD_RESET)
            tokens.map { it.hash } shouldBe listOf(FakeSecrets.token(0).hash(), FakeSecrets.token(1).hash())
            tokens[0].usedAt shouldBe NOW.plus(Duration.ofMinutes(1))
            tokens[1].usedAt.shouldBeNull()
            tokens[1].expiresAt shouldBe NOW.plus(Duration.ofMinutes(61))
            val event = harness.events.resets.last()
            event.resetToken shouldBe FakeSecrets.token(1)
            event.tokenExpiresAt shouldBe tokens[1].expiresAt
            event.recipient.email shouldBe ada.email
            event.recipient.preferredChannels shouldBe listOf(NotificationChannel.EMAIL)
            harness.events.resets.size shouldBe 2
        }

        test("unknown, malformed and deleted addresses get the same answer and nothing happens") {
            val harness = Harness()
            val ada = harness.shopper()
            harness.accounts.store(ada.anonymise(Pseudonym.of(ada.id), NOW).value())
            val request = RequestPasswordReset(harness.store)

            request("nobody@example.test").value()
            request("not an email").value()
            request(ADA).value()

            harness.tokens.tokens.values
                .shouldBeEmpty()
            harness.events.all.shouldBeEmpty()
            harness.transactions.transactions shouldBe 0
        }

        test("completing the reset sets the new password, clears the lock and revokes every session") {
            val harness = Harness()
            val ada = harness.shopper()
            SignIn(harness.store)(Credentials(ADA, PASSWORD, "s")).value()
            harness.accounts.store(harness.accounts[ada.id].copy(throttle = SignInThrottle(3, null)))
            RequestPasswordReset(harness.store)(ADA).value()
            harness.clock.advance(Duration.ofMinutes(30))

            CompletePasswordReset(harness.store)(ResetCompletion(FakeSecrets.token(1).value, NEW_PASSWORD)).value()

            val account = harness.accounts[ada.id]
            account.passwordHash shouldBe PasswordHash("hash:$NEW_PASSWORD")
            account.throttle shouldBe SignInThrottle.CLEAR
            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt shouldBe NOW.plus(Duration.ofMinutes(30))
            harness.tokens
                .of(ada.id, TokenPurpose.PASSWORD_RESET)
                .single()
                .usedAt shouldBe
                NOW.plus(Duration.ofMinutes(30))
            SignIn(harness.store)(Credentials(ADA, PASSWORD, "s")).error() shouldBe IdentityError.InvalidCredentials
            SignIn(harness.store)(Credentials(ADA, NEW_PASSWORD, "s")).value()
            CompletePasswordReset(
                harness.store,
            )(ResetCompletion(FakeSecrets.token(1).value, "Another-passphrase!")).error() shouldBe
                IdentityError.InvalidToken
            ResetCompletion("t", NEW_PASSWORD).toString() shouldNotContain NEW_PASSWORD
        }

        test("a new password must meet the policy and a refused one does not spend the token") {
            val harness = Harness()
            harness.shopper()
            RequestPasswordReset(harness.store)(ADA).value()
            val complete = CompletePasswordReset(harness.store)

            complete(ResetCompletion(FakeSecrets.token(0).value, "short")).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("newPassword", "must be at least 12 characters")))
            complete(ResetCompletion(FakeSecrets.token(0).value, ADA)).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("newPassword", "must not be the email address")))

            harness.tokens.tokens.values
                .single()
                .usedAt
                .shouldBeNull()
            harness.hasher.hashed shouldBe 0
            complete(ResetCompletion(FakeSecrets.token(0).value, NEW_PASSWORD)).value()
        }

        test("malformed, unknown, expired, raced tokens and tokens of deleted accounts are refused") {
            val harness = Harness()
            val ada = harness.shopper()
            RequestPasswordReset(harness.store)(ADA).value()
            val complete = CompletePasswordReset(harness.store)
            val token = FakeSecrets.token(0).value

            complete(ResetCompletion("short", NEW_PASSWORD)).error() shouldBe IdentityError.InvalidToken
            complete(ResetCompletion(FakeSecrets.token(5).value, NEW_PASSWORD)).error() shouldBe
                IdentityError.InvalidToken
            harness.tokens.losingMarks = 1
            complete(ResetCompletion(token, NEW_PASSWORD)).error() shouldBe IdentityError.InvalidToken
            harness.accounts.losingUpdates = 1
            complete(ResetCompletion(token, NEW_PASSWORD)).error() shouldBe IdentityError.ConcurrentUpdate
            harness.tokens.tokens.values
                .single()
                .usedAt
                .shouldBeNull()
            harness.accounts[ada.id].passwordHash shouldBe PasswordHash("hash:$PASSWORD")

            harness.clock.advance(Duration.ofHours(1))
            complete(ResetCompletion(token, NEW_PASSWORD)).error() shouldBe IdentityError.InvalidToken

            harness.clock.instant = NOW
            harness.accounts.store(ada.anonymise(Pseudonym.of(ada.id), NOW).value())
            complete(ResetCompletion(token, NEW_PASSWORD)).error() shouldBe IdentityError.InvalidToken
            harness.accounts[ada.id].status shouldBe AccountStatus.DELETED
            harness.accounts[ada.id].passwordHash.shouldBeNull()
            harness.tokens.tokens.values
                .single()
                .shouldNotBeNull()
        }

        test("a token of another purpose is not a reset token") {
            val harness = Harness()
            RegisterAccount(harness.store)(Registration(ADA, PASSWORD, null)).value()

            CompletePasswordReset(
                harness.store,
            )(ResetCompletion(FakeSecrets.token(0).value, NEW_PASSWORD)).error() shouldBe
                IdentityError.InvalidToken
        }
    })

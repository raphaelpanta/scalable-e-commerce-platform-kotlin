package com.ecommerce.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import java.time.Duration

private val EMAIL_ONLY = setOf(NotificationChannel.EMAIL)
private val BOTH = setOf(NotificationChannel.EMAIL, NotificationChannel.SMS)

class PreferencesSpec :
    FunSpec({
        test("new accounts are notified by email only and have no phone") {
            val owner = accountId()
            val preference = NotificationPreference.default(owner)

            preference.accountId shouldBe owner
            preference.channels shouldBe EMAIL_ONLY
            preference.phone.shouldBeNull()
            preference.phoneVerified.shouldBeFalse()
            preference.hasVerifiedPhone.shouldBeFalse()
            preference.permittedChannels() shouldBe listOf(NotificationChannel.EMAIL)
        }

        test("sms is permitted only with a verified phone, and only when chosen") {
            checkAll(
                Arb.set(Arb.enum<NotificationChannel>(), 0..2),
                Arb.boolean(),
                Arb.boolean(),
            ) { chosen, hasPhone, verified ->
                val preference = NotificationPreference(accountId(), chosen, if (hasPhone) phone() else null, verified)
                val expected =
                    NotificationChannel.entries.filter {
                        it in chosen && (it == NotificationChannel.EMAIL || (hasPhone && verified))
                    }
                preference.permittedChannels() shouldBe expected
                preference.hasVerifiedPhone shouldBe (hasPhone && verified)
            }
        }

        test("choosing channels needs at least one, and sms needs a verified phone") {
            val unverified = NotificationPreference.default(accountId())
            unverified.choose(emptySet()).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("channels", "must not be empty")))
            unverified.choose(BOTH).error() shouldBe IdentityError.SmsRequiresVerifiedPhone
            unverified.copy(phone = phone()).choose(BOTH).error() shouldBe IdentityError.SmsRequiresVerifiedPhone
            unverified.copy(phoneVerified = true).choose(BOTH).error() shouldBe IdentityError.SmsRequiresVerifiedPhone
            unverified.choose(EMAIL_ONLY).value().channels shouldBe EMAIL_ONLY

            val verified = unverified.withVerifiedPhone(phone())
            verified.phone shouldBe phone()
            verified.phoneVerified.shouldBeTrue()
            verified.channels shouldBe EMAIL_ONLY
            verified.choose(BOTH).value().permittedChannels() shouldBe
                listOf(NotificationChannel.EMAIL, NotificationChannel.SMS)
            verified.choose(setOf(NotificationChannel.SMS)).value().permittedChannels() shouldBe
                listOf(NotificationChannel.SMS)
        }

        test("a phone code is valid for 10 minutes and 5 attempts, and wrong codes use attempts up") {
            val owner = accountId()
            val issued = PhoneVerification.issue(owner, phone(), code("123456"), NOW)

            issued.accountId shouldBe owner
            issued.codeHash shouldBe code("123456").hashFor(owner)
            issued.expiresAt shouldBe NOW.plus(Duration.ofMinutes(10))
            issued.attempts shouldBe 0

            var current = issued
            repeat(PhoneVerification.MAX_ATTEMPTS) { attempt ->
                val outcome = current.attempt(code("000000"), NOW)
                outcome.result.error() shouldBe IdentityError.WrongCode
                outcome.verification.attempts shouldBe attempt + 1
                current = outcome.verification
            }
            val exhausted = current.attempt(code("123456"), NOW)
            exhausted.result.error() shouldBe IdentityError.TooManyCodeAttempts
            exhausted.verification shouldBe current

            issued
                .copy(
                    attempts = PhoneVerification.MAX_ATTEMPTS - 1,
                ).attempt(code("123456"), NOW)
                .result
                .value() shouldBe
                phone()
        }

        test("the right code confirms the phone before expiry and never after") {
            checkAll(Arb.int(0..1200)) { seconds ->
                val issued = PhoneVerification.issue(accountId(), phone(), code("654321"), NOW)
                val outcome = issued.attempt(code("654321"), NOW.plusSeconds(seconds.toLong()))
                if (seconds < 600) {
                    outcome.result.value() shouldBe phone()
                } else {
                    outcome.result.error() shouldBe IdentityError.CodeExpired
                }
                outcome.verification shouldBe issued
            }
            val issued = PhoneVerification.issue(accountId(), phone(), code("654321"), NOW)
            issued.attempt(code("654321"), NOW.plus(Duration.ofMinutes(10)).minusNanos(1)).result.value() shouldBe
                phone()
        }

        test("a new code can be sent 30 seconds after the previous one") {
            val issued = PhoneVerification.issue(accountId(), phone(), code(), NOW)
            issued.resendWait(NOW) shouldBe Duration.ofSeconds(30)
            issued.resendWait(NOW.plusSeconds(10)) shouldBe Duration.ofSeconds(20)
            issued.resendWait(NOW.plusSeconds(30)) shouldBe Duration.ZERO
            issued.resendWait(NOW.plusSeconds(31)) shouldBe Duration.ZERO
        }

        test("the recipient snapshot carries the phone only when sms is permitted") {
            val owner = account()
            val emailOnly = NotificationPreference.default(owner.id)
            RecipientSnapshot.of(owner, emailOnly) shouldBe
                RecipientSnapshot(owner.id, owner.email, null, listOf(NotificationChannel.EMAIL))

            val withPhone = emailOnly.withVerifiedPhone(phone())
            RecipientSnapshot.of(owner, withPhone).phone.shouldBeNull()
            val sms = withPhone.choose(BOTH).value()
            RecipientSnapshot.of(owner, sms) shouldBe
                RecipientSnapshot(
                    owner.id,
                    owner.email,
                    phone(),
                    listOf(NotificationChannel.EMAIL, NotificationChannel.SMS),
                )
        }

        test("the contact of a live account lists its permitted channels; an anonymised one has none") {
            val owner = account()
            val sms =
                NotificationPreference
                    .default(owner.id)
                    .withVerifiedPhone(phone())
                    .choose(BOTH)
                    .value()

            AccountContact.of(owner, sms) shouldBe
                AccountContact(
                    owner.id,
                    owner.email,
                    phone(),
                    true,
                    listOf(NotificationChannel.EMAIL, NotificationChannel.SMS),
                    false,
                )
            AccountContact.of(owner, NotificationPreference.default(owner.id)) shouldBe
                AccountContact(owner.id, owner.email, null, false, listOf(NotificationChannel.EMAIL), false)

            val deleted = owner.anonymise(Pseudonym("anon-4f9c2d71"), NOW).value()
            val contact = AccountContact.of(deleted, sms)
            contact.email.value shouldBe "anon-4f9c2d71@anonymised.invalid"
            contact.phone.shouldBeNull()
            contact.phoneVerified.shouldBeFalse()
            contact.channels.shouldBeEmpty()
            contact.anonymised.shouldBeTrue()
        }

        test("invalid inputs name at least one broken rule") {
            shouldThrow<IllegalArgumentException> { IdentityError.Invalid(emptyList()) }
            shouldThrow<IllegalArgumentException> { IdentityError.Malformed(emptyList()) }
            IdentityError
                .Malformed(listOf(FieldError("a", "b")))
                .errors
                .single()
                .field shouldBe "a"
        }
    })

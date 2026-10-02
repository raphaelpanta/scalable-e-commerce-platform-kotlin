package com.ecommerce.identity.application

import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.VerificationCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID

class PreferencesTest :
    FunSpec({
        test("preferences default to email only and sms needs a verified phone") {
            val harness = Harness()
            val ada = harness.shopper()
            val update = UpdateNotificationPreferences(harness.store)

            GetNotificationPreferences(harness.store)(ada.id).value() shouldBe NotificationPreference.default(ada.id)
            update(ada.id, listOf("email", "sms")).error() shouldBe IdentityError.SmsRequiresVerifiedPhone
            update(ada.id, listOf("email", "push")).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("channels", "must be email or sms")))
            update(ada.id, emptyList()).error() shouldBe
                IdentityError.Invalid(listOf(FieldError("channels", "must not be empty")))
            harness.preferences.preferences.values
                .shouldBeEmpty()
            update(ada.id, listOf("email", "email")).value().channels shouldBe setOf(NotificationChannel.EMAIL)
            harness.preferences.preferences[ada.id]?.channels shouldBe setOf(NotificationChannel.EMAIL)
            GetNotificationPreferences(harness.store)(AccountId(UUID.randomUUID())).error() shouldBe
                IdentityError.AccountNotFound
            update(AccountId(UUID.randomUUID()), listOf("email")).error() shouldBe IdentityError.AccountNotFound
        }

        test("a phone code is sent by SMS, stored as a hash, and confirming it enables sms") {
            val harness = Harness()
            val ada = harness.shopper()

            RequestPhoneVerification(harness.store)(ada.id, " +5511987654321 ").value()

            val (to, text) = harness.sms.sent.single()
            to shouldBe phone()
            text shouldBe "Your ecommerce verification code is 100000. It expires in 10 minutes."
            val pending = harness.preferences.verifications[ada.id].shouldNotBeNull()
            pending.codeHash shouldBe VerificationCode.of("100000").value().hashFor(ada.id)
            pending.expiresAt shouldBe NOW.plus(Duration.ofMinutes(10))

            ConfirmPhoneVerification(harness.store)(ada.id, "100000").value()

            val preference = harness.preferences.preferences[ada.id].shouldNotBeNull()
            preference.phone shouldBe phone()
            preference.phoneVerified shouldBe true
            harness.preferences.verifications[ada.id].shouldBeNull()
            UpdateNotificationPreferences(
                harness.store,
            )(ada.id, listOf("email", "sms")).value().permittedChannels() shouldBe
                listOf(NotificationChannel.EMAIL, NotificationChannel.SMS)
        }

        test("a new code may be requested 30 seconds after the previous one") {
            val harness = Harness()
            val ada = harness.shopper()
            val request = RequestPhoneVerification(harness.store)
            request(ada.id, "+5511987654321").value()
            harness.clock.advance(Duration.ofSeconds(10))

            request(ada.id, "+5511987654321").error() shouldBe IdentityError.Throttled(Duration.ofSeconds(20))
            harness.clock.advance(Duration.ofSeconds(20))
            request(ada.id, "+5511900000000").value()

            harness.sms.sent.size shouldBe 2
            harness.preferences.verifications[ada.id]?.phone shouldBe phone("+5511900000000")
        }

        test("malformed numbers and codes are 400s; unknown accounts are refused; a refused SMS is unavailable") {
            val harness = Harness()
            val ada = harness.shopper()

            RequestPhoneVerification(harness.store)(ada.id, "123").error() shouldBe
                IdentityError.Malformed(
                    listOf(FieldError("phoneNumber", "must be in E.164 format (+ and 8 to 15 digits)")),
                )
            ConfirmPhoneVerification(harness.store)(ada.id, "12").error() shouldBe
                IdentityError.Malformed(listOf(FieldError("code", "must be 6 digits")))
            RequestPhoneVerification(harness.store)(AccountId(UUID.randomUUID()), "+5511987654321").error() shouldBe
                IdentityError.AccountNotFound
            ConfirmPhoneVerification(harness.store)(AccountId(UUID.randomUUID()), "123456").error() shouldBe
                IdentityError.AccountNotFound
            ConfirmPhoneVerification(harness.store)(ada.id, "123456").error() shouldBe
                IdentityError.NoPendingPhoneVerification
            harness.sms.accepting = false
            RequestPhoneVerification(harness.store)(ada.id, "+5511987654321").error() shouldBe
                IdentityError.SmsUnavailable
            harness.sms.sent.shouldBeEmpty()
        }

        test("wrong codes use attempts up, and the pending number stays unverified") {
            val harness = Harness()
            val ada = harness.shopper()
            RequestPhoneVerification(harness.store)(ada.id, "+5511987654321").value()
            val confirm = ConfirmPhoneVerification(harness.store)

            repeat(PhoneVerification.MAX_ATTEMPTS) {
                confirm(ada.id, "999999").error() shouldBe IdentityError.WrongCode
            }
            harness.preferences.verifications[ada.id]?.attempts shouldBe PhoneVerification.MAX_ATTEMPTS
            confirm(ada.id, "100000").error() shouldBe IdentityError.TooManyCodeAttempts
            harness.preferences.preferences[ada.id].shouldBeNull()

            harness.clock.advance(Duration.ofMinutes(1))
            RequestPhoneVerification(harness.store)(ada.id, "+5511987654321").value()
            harness.clock.advance(Duration.ofMinutes(10))
            confirm(ada.id, "100001").error() shouldBe IdentityError.CodeExpired
            harness.preferences.verifications[ada.id]?.attempts shouldBe 0
        }
    })

package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.NotificationChannel
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.PhoneNumber
import com.ecommerce.identity.domain.PhoneVerification
import com.ecommerce.identity.domain.VerificationCode
import java.time.Duration

/** `getOwnNotificationPreferences`. */
class GetNotificationPreferences(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(accountId: AccountId): Either<IdentityError, NotificationPreference> =
        either {
            store.liveAccount(accountId).bind()
            store.preferenceOf(accountId)
        }
}

/** `updateOwnNotificationPreferences` (FR-020): at least one channel; `sms` only with a verified phone. */
class UpdateNotificationPreferences(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        channels: List<String>,
    ): Either<IdentityError, NotificationPreference> =
        either {
            val unknown = channels.filter { NotificationChannel.fromCode(it) == null }
            ensure(unknown.isEmpty()) {
                IdentityError.Invalid(listOf(FieldError("channels", "must be email or sms")))
            }
            store.liveAccount(accountId).bind()
            val chosen =
                store
                    .preferenceOf(
                        accountId,
                    ).choose(channels.mapNotNull(NotificationChannel::fromCode).toSet())
                    .bind()
            store.preferences.save(chosen)
            chosen
        }
}

/**
 * `requestPhoneVerification`: sends a fresh 6-digit code by SMS (10 minutes, 5 attempts) and keeps only its hash;
 * a new code may be requested 30 seconds after the previous one. The number becomes the account's verified phone
 * once the code is confirmed.
 */
class RequestPhoneVerification(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        rawPhone: String,
    ): Either<IdentityError, Unit> =
        either {
            val phone = PhoneNumber.of(rawPhone).mapLeft { IdentityError.Malformed(listOf(it)) }.bind()
            store.liveAccount(accountId).bind()
            val now = store.clock.now()
            val wait = store.preferences.pendingVerification(accountId)?.resendWait(now) ?: Duration.ZERO
            ensure(wait == Duration.ZERO) { IdentityError.Throttled(wait) }
            val code = store.secrets.verificationCode()
            store.preferences.saveVerification(PhoneVerification.issue(accountId, phone, code, now))
            ensure(store.sms.send(phone, messageFor(code))) { IdentityError.SmsUnavailable }
        }

    companion object {
        /** The SMS text: the code and how long it stays valid. */
        fun messageFor(code: VerificationCode): String =
            "Your ecommerce verification code is ${code.value}. It expires in ${PhoneVerification.TTL.toMinutes()} minutes."
    }
}

/** `confirmPhoneVerification`: the right code makes the pending number the account's verified phone. */
class ConfirmPhoneVerification(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        rawCode: String,
    ): Either<IdentityError, Unit> =
        either {
            val code = VerificationCode.of(rawCode).mapLeft { IdentityError.Malformed(listOf(it)) }.bind()
            store.liveAccount(accountId).bind()
            val pending =
                ensureNotNull(
                    store.preferences.pendingVerification(accountId),
                ) { IdentityError.NoPendingPhoneVerification }
            val attempt = pending.attempt(code, store.clock.now())
            if (attempt.verification != pending) store.preferences.saveVerification(attempt.verification)
            val phone = attempt.result.bind()
            store.preferences.save(store.preferenceOf(accountId).withVerifiedPhone(phone))
            store.preferences.deleteVerification(accountId)
        }
}

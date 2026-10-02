package com.ecommerce.identity.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant

/**
 * The notification preferences of an account (data-model section 3.1, FR-020): the chosen [channels] (email by
 * default) and the phone number with its verification state. `sms` may only be chosen with a verified number.
 */
data class NotificationPreference(
    val accountId: AccountId,
    val channels: Set<NotificationChannel>,
    val phone: PhoneNumber?,
    val phoneVerified: Boolean,
) {
    /** True when a phone number is registered and verified. */
    val hasVerifiedPhone: Boolean get() = phone != null && phoneVerified

    /** The channels notifications may use right now: the chosen ones, `sms` only with a verified phone. */
    fun permittedChannels(): List<NotificationChannel> =
        NotificationChannel.entries.filter { it in channels && (it == NotificationChannel.EMAIL || hasVerifiedPhone) }

    /** Chooses [chosen] (at least one channel; `sms` only with a verified phone). */
    fun choose(chosen: Set<NotificationChannel>): Either<IdentityError, NotificationPreference> =
        when {
            chosen.isEmpty() -> IdentityError.Invalid(listOf(FieldError("channels", "must not be empty"))).left()
            NotificationChannel.SMS in chosen && !hasVerifiedPhone -> IdentityError.SmsRequiresVerifiedPhone.left()
            else -> copy(channels = chosen).right()
        }

    /** [verified] becomes the account's phone number, verified; the chosen channels stay as they are. */
    fun withVerifiedPhone(verified: PhoneNumber): NotificationPreference = copy(phone = verified, phoneVerified = true)

    companion object {
        /** Email only, no phone: the preferences of a new account. */
        fun default(accountId: AccountId): NotificationPreference =
            NotificationPreference(accountId, setOf(NotificationChannel.EMAIL), null, false)
    }
}

/**
 * A pending phone verification (data-model section 3.1 `PhoneVerification`): a 6-digit code, stored as a hash, valid
 * for [TTL] and [MAX_ATTEMPTS] tries; a new code may be requested [RESEND_INTERVAL] after the previous one.
 */
data class PhoneVerification(
    val accountId: AccountId,
    val phone: PhoneNumber,
    val codeHash: TokenHash,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val attempts: Int,
) {
    /** The outcome of one confirmation attempt: the verification to store and the verified number or the error. */
    data class Attempt(
        val verification: PhoneVerification,
        val result: Either<IdentityError, PhoneNumber>,
    )

    /** How long until another code may be sent at [now] (zero when it may be sent now). */
    fun resendWait(now: Instant): Duration {
        val allowedAt = issuedAt.plus(RESEND_INTERVAL)
        return if (now.isBefore(allowedAt)) Duration.between(now, allowedAt) else Duration.ZERO
    }

    /** Tries [code] at [now]; a wrong code uses up one attempt. */
    fun attempt(
        code: VerificationCode,
        now: Instant,
    ): Attempt =
        when {
            !now.isBefore(expiresAt) -> Attempt(this, IdentityError.CodeExpired.left())
            attempts >= MAX_ATTEMPTS -> Attempt(this, IdentityError.TooManyCodeAttempts.left())
            Digests.sameHash(code.hashFor(accountId), codeHash) -> Attempt(this, phone.right())
            else -> Attempt(copy(attempts = attempts + 1), IdentityError.WrongCode.left())
        }

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
        val RESEND_INTERVAL: Duration = Duration.ofSeconds(30)
        const val MAX_ATTEMPTS: Int = 5

        fun issue(
            accountId: AccountId,
            phone: PhoneNumber,
            code: VerificationCode,
            now: Instant,
        ): PhoneVerification = PhoneVerification(accountId, phone, code.hashFor(accountId), now, now.plus(TTL), 0)
    }
}

/**
 * Point-in-time contact data carried by the account events (events.yaml `RecipientSnapshot`): the phone only when it
 * is verified and SMS is permitted.
 */
data class RecipientSnapshot(
    val accountId: AccountId,
    val email: Email,
    val phone: PhoneNumber?,
    val preferredChannels: List<NotificationChannel>,
) {
    companion object {
        fun of(
            account: Account,
            preference: NotificationPreference,
        ): RecipientSnapshot {
            val channels = preference.permittedChannels()
            val phone = if (NotificationChannel.SMS in channels) preference.phone else null
            return RecipientSnapshot(account.id, account.email, phone, channels)
        }
    }
}

/**
 * The contact details and permitted channels of an account for the notification service (identity-internal.yaml
 * `AccountContact`): an anonymised account answers with its placeholder email, no phone and no channel.
 */
data class AccountContact(
    val accountId: AccountId,
    val email: Email,
    val phone: PhoneNumber?,
    val phoneVerified: Boolean,
    val channels: List<NotificationChannel>,
    val anonymised: Boolean,
) {
    companion object {
        fun of(
            account: Account,
            preference: NotificationPreference,
        ): AccountContact =
            if (account.isDeleted) {
                AccountContact(account.id, account.email, null, false, emptyList(), true)
            } else {
                AccountContact(
                    account.id,
                    account.email,
                    preference.phone,
                    preference.hasVerifiedPhone,
                    preference.permittedChannels(),
                    false,
                )
            }
    }
}

/** `AccountRegistered`: the verification token travels to the notification service (sensitive, never logged). */
data class AccountRegistered(
    val recipient: RecipientSnapshot,
    val verificationToken: OpaqueToken,
    val tokenExpiresAt: Instant,
)

/** `AccountVerified`: the email was verified at [verifiedAt]. */
data class AccountVerified(
    val recipient: RecipientSnapshot,
    val verifiedAt: Instant,
)

/** `PasswordResetRequested`: the reset token travels to the notification service (sensitive, never logged). */
data class PasswordResetRequested(
    val recipient: RecipientSnapshot,
    val resetToken: OpaqueToken,
    val tokenExpiresAt: Instant,
)

/** `AccountDeleted`: consumers replace the account by [pseudonym] and drop its personal data. */
data class AccountDeleted(
    val accountId: AccountId,
    val pseudonym: Pseudonym,
    val deletedAt: Instant,
)

package com.ecommerce.notification.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.Locale

private const val MASK = "***"
private const val MAX_EMAIL_LENGTH = 254
private val E164 = Regex("\\+[1-9][0-9]{7,14}")

/**
 * An email address (data-model §2 `Email`): trimmed and lower-cased, at most 254 characters, one `@`, a non-empty
 * local part, a domain with an inner dot, no whitespace. Personal data: `toString()` is masked.
 */
@JvmInline
value class EmailAddress private constructor(
    val value: String,
) {
    override fun toString(): String = "$MASK@" + value.substringAfter('@', "")

    companion object {
        /** Normalises and validates [raw]; the left side names the broken rule. */
        fun of(raw: String): Either<String, EmailAddress> {
            val value = raw.trim().lowercase(Locale.ROOT)
            val local = value.substringBefore('@')
            val domain = value.substringAfter('@', "")
            val dotted = domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
            return when {
                value.length > MAX_EMAIL_LENGTH -> "email must be at most $MAX_EMAIL_LENGTH characters".left()
                value.any { it.isWhitespace() || it.isISOControl() } -> "email must not contain whitespace".left()
                value.count { it == '@' } != 1 -> "email must contain exactly one @".left()
                local.isEmpty() -> "email must have a local part".left()
                !dotted -> "email must have a domain containing a dot".left()
                else -> EmailAddress(value).right()
            }
        }
    }
}

/** A phone number in E.164 form (data-model §2 `PhoneNumber`). Personal data: `toString()` is masked. */
@JvmInline
value class PhoneNumber private constructor(
    val value: String,
) {
    /** The digits without the leading `+`. */
    val digits: String get() = value.substring(1)

    override fun toString(): String = MASK

    companion object {
        /** Validates [raw] (surrounding blanks ignored). */
        fun of(raw: String): Either<String, PhoneNumber> {
            val value = raw.trim()
            return if (E164.matches(value)) PhoneNumber(value).right() else "phone must be in E.164 format".left()
        }
    }
}

/** Where one notification goes: an email address or a phone number, as text. Personal data, masked. */
@JvmInline
value class RecipientAddress(
    val value: String,
) {
    override fun toString(): String = MASK
}

/**
 * Contact details and permitted channels of an account at the time an event is processed (identity
 * `AccountContact`, or the event's `RecipientSnapshot` when identity cannot answer). `channels` are the channels
 * the shopper allows now; SMS additionally needs a verified [phone].
 */
data class RecipientContact(
    val email: EmailAddress?,
    val phone: PhoneNumber?,
    val phoneVerified: Boolean,
    val channels: Set<NotificationChannel>,
    val anonymised: Boolean,
) {
    companion object {
        /** An anonymised (deleted) account: nothing may be sent (FR-007). */
        val ANONYMISED: RecipientContact = RecipientContact(null, null, false, emptySet(), true)
    }
}

/**
 * The recipient read model (data-model §3.6 `Recipient`), keyed by account: built from `AccountRegistered` and
 * `AccountVerified`, anonymised on `AccountDeleted`. An anonymised recipient stays anonymised.
 */
data class Recipient(
    val accountId: AccountId,
    val emailVerified: Boolean,
    val anonymised: Boolean,
) {
    /** After `AccountVerified`. */
    fun verified(): Recipient = if (anonymised) this else copy(emailVerified = true)

    /** After `AccountDeleted`. */
    fun anonymise(): Recipient = copy(emailVerified = false, anonymised = true)

    companion object {
        /** A recipient first seen through `AccountRegistered` (or any later account event). */
        fun registered(accountId: AccountId): Recipient =
            Recipient(accountId, emailVerified = false, anonymised = false)
    }
}

/** The outcome of channel selection for one channel. */
sealed interface ChannelDecision {
    val channel: NotificationChannel

    /** Send on [channel] to [address]. */
    data class Deliver(
        override val channel: NotificationChannel,
        val address: RecipientAddress,
    ) : ChannelDecision

    /** Record the message on [channel] as suppressed: the recipient may not be messaged. */
    data class Suppress(
        override val channel: NotificationChannel,
    ) : ChannelDecision
}

/**
 * Channel selection (FR-018, data-model §3.6): an anonymised account (or one without an email address) gets one
 * suppressed email record and nothing else; otherwise email when the shopper allows it, and always for the
 * [NotificationKind.security] kinds; SMS in addition when the shopper allows it and the phone is verified, never
 * for security kinds.
 */
object ChannelSelection {
    /** The channels [kind] goes to for [contact], email first. */
    fun select(
        kind: NotificationKind,
        contact: RecipientContact,
    ): List<ChannelDecision> {
        val email = contact.email
        if (contact.anonymised || email == null) return listOf(ChannelDecision.Suppress(NotificationChannel.EMAIL))
        val byEmail =
            if (kind.security || NotificationChannel.EMAIL in contact.channels) {
                ChannelDecision.Deliver(NotificationChannel.EMAIL, RecipientAddress(email.value))
            } else {
                null
            }
        return listOfNotNull(byEmail, smsDecision(kind, contact))
    }

    private fun smsDecision(
        kind: NotificationKind,
        contact: RecipientContact,
    ): ChannelDecision? {
        val phone = contact.phone
        val allowed = !kind.security && contact.phoneVerified && NotificationChannel.SMS in contact.channels
        return if (allowed && phone != null) {
            ChannelDecision.Deliver(NotificationChannel.SMS, RecipientAddress(phone.value))
        } else {
            null
        }
    }
}

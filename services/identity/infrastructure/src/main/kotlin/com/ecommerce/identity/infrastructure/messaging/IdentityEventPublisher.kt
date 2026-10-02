package com.ecommerce.identity.infrastructure.messaging

import com.ecommerce.identity.application.IdentityEvents
import com.ecommerce.identity.domain.AccountDeleted
import com.ecommerce.identity.domain.AccountRegistered
import com.ecommerce.identity.domain.AccountVerified
import com.ecommerce.identity.domain.PasswordResetRequested
import com.ecommerce.identity.domain.RecipientSnapshot
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.observability.Pii
import java.time.Instant
import java.util.UUID

/** events.yaml `RecipientSnapshot`: personal data, `toString()` keeps the account id only. */
data class RecipientPayload(
    val accountId: UUID,
    @Pii val email: String,
    @Pii val phone: String?,
    val preferredChannels: List<String>,
) {
    override fun toString(): String = "RecipientPayload(accountId=$accountId)"

    companion object {
        fun of(recipient: RecipientSnapshot): RecipientPayload =
            RecipientPayload(
                recipient.accountId.value,
                recipient.email.value,
                recipient.phone?.value,
                recipient.preferredChannels.map { it.code },
            )
    }
}

/** events.yaml `AccountRegisteredPayload`. The verification token is sensitive: never logged (masked `toString()`). */
data class AccountRegisteredPayload(
    val accountId: UUID,
    val recipient: RecipientPayload,
    @Pii val verificationToken: String,
    val tokenExpiresAt: Instant,
) {
    override fun toString(): String = "AccountRegisteredPayload(accountId=$accountId, verificationToken=****)"
}

/** events.yaml `AccountVerifiedPayload`. */
data class AccountVerifiedPayload(
    val accountId: UUID,
    val recipient: RecipientPayload,
    val verifiedAt: Instant,
)

/** events.yaml `PasswordResetRequestedPayload`. The reset token is sensitive: never logged (masked `toString()`). */
data class PasswordResetRequestedPayload(
    val accountId: UUID,
    val recipient: RecipientPayload,
    @Pii val resetToken: String,
    val tokenExpiresAt: Instant,
) {
    override fun toString(): String = "PasswordResetRequestedPayload(accountId=$accountId, resetToken=****)"
}

/** events.yaml `AccountDeletedPayload`. */
data class AccountDeletedPayload(
    val accountId: UUID,
    val pseudonym: String,
    val deletedAt: Instant,
)

/**
 * The envelopes of the account events (`identity.account.v1`, key = account id), built by [envelopes] with the
 * producer `identity`. Shared by [IdentityEventPublisher] and the provider verification of the message pacts, so
 * that the verified envelopes are exactly the published ones.
 */
class IdentityEnvelopes(
    private val envelopes: EnvelopeFactory,
) {
    fun accountRegistered(
        event: AccountRegistered,
        correlationId: String,
    ): Envelope<AccountRegisteredPayload> =
        envelopes.create(
            EventType.AccountRegistered,
            event.recipient.accountId.value,
            correlationId,
            AccountRegisteredPayload(
                event.recipient.accountId.value,
                RecipientPayload.of(event.recipient),
                event.verificationToken.value,
                event.tokenExpiresAt,
            ),
        )

    fun accountVerified(
        event: AccountVerified,
        correlationId: String,
    ): Envelope<AccountVerifiedPayload> =
        envelopes.create(
            EventType.AccountVerified,
            event.recipient.accountId.value,
            correlationId,
            AccountVerifiedPayload(
                event.recipient.accountId.value,
                RecipientPayload.of(event.recipient),
                event.verifiedAt,
            ),
        )

    fun passwordResetRequested(
        event: PasswordResetRequested,
        correlationId: String,
    ): Envelope<PasswordResetRequestedPayload> =
        envelopes.create(
            EventType.PasswordResetRequested,
            event.recipient.accountId.value,
            correlationId,
            PasswordResetRequestedPayload(
                event.recipient.accountId.value,
                RecipientPayload.of(event.recipient),
                event.resetToken.value,
                event.tokenExpiresAt,
            ),
        )

    fun accountDeleted(
        event: AccountDeleted,
        correlationId: String,
    ): Envelope<AccountDeletedPayload> =
        envelopes.create(
            EventType.AccountDeleted,
            event.accountId.value,
            correlationId,
            AccountDeletedPayload(event.accountId.value, event.pseudonym.value, event.deletedAt),
        )
}

/**
 * Outbound adapter: publishes the account events through the transactional outbox of `libs/platform-messaging`, in
 * the caller's transaction, with the correlation id of the request (a fresh one outside a request).
 */
class IdentityEventPublisher(
    private val outbox: OutboxPublisher,
    private val envelopes: IdentityEnvelopes,
) : IdentityEvents {
    override suspend fun accountRegistered(event: AccountRegistered) =
        outbox.publish(envelopes.accountRegistered(event, correlationId()))

    override suspend fun accountVerified(event: AccountVerified) =
        outbox.publish(envelopes.accountVerified(event, correlationId()))

    override suspend fun passwordResetRequested(event: PasswordResetRequested) =
        outbox.publish(envelopes.passwordResetRequested(event, correlationId()))

    override suspend fun accountDeleted(event: AccountDeleted) =
        outbox.publish(envelopes.accountDeleted(event, correlationId()))

    private suspend fun correlationId(): String = CorrelationIds.current() ?: UUID.randomUUID().toString()
}

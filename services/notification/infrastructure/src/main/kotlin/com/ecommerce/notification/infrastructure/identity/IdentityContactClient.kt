package com.ecommerce.notification.infrastructure.identity

import com.ecommerce.notification.application.ContactLookup
import com.ecommerce.notification.application.RecipientLookupPort
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.EmailAddress
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.PhoneNumber
import com.ecommerce.notification.domain.RecipientContact
import com.ecommerce.platform.http.awaitBodyOrProblem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import java.util.UUID

private const val NOT_FOUND = 404
private const val CORRELATION_HEADER = "X-Correlation-Id"
private val CORRELATION_ID = Regex("^[A-Za-z0-9-]{1,64}$")

/** `AccountContact` of identity-internal.yaml; only the members this service reads. Personal data, never logged. */
data class AccountContactResponse(
    val accountId: UUID,
    val email: String,
    val phoneNumber: String? = null,
    val phoneVerified: Boolean = false,
    val channels: List<String> = emptyList(),
    val anonymised: Boolean = false,
) {
    /** The contact as the domain sees it; unusable values (an unknown channel, a malformed number) are dropped. */
    fun toContact(): RecipientContact =
        RecipientContact(
            email = EmailAddress.of(email).getOrNull(),
            phone = phoneNumber?.let { PhoneNumber.of(it).getOrNull() },
            phoneVerified = phoneVerified,
            channels = channels.mapNotNull(NotificationChannel::fromWire).toSet(),
            anonymised = anonymised,
        )

    override fun toString(): String = "AccountContactResponse(accountId=$accountId, anonymised=$anonymised)"
}

/**
 * [RecipientLookupPort] over `GET /internal/accounts/{accountId}/contact` of identity (consumer side of the
 * `notification` → `identity` Pact). The client comes from `WebClientDefaults.internalClient` (internal token,
 * timeouts, retries on connection errors only); the event's correlation id is sent as `X-Correlation-Id`. A 404 is
 * [ContactLookup.Unknown]; any other error, a timeout or an unreachable identity is [ContactLookup.Unavailable].
 */
class IdentityContactClient(
    private val client: WebClient,
) : RecipientLookupPort {
    override suspend fun lookup(
        accountId: AccountId,
        correlationId: String,
    ): ContactLookup =
        try {
            client
                .get()
                .uri("/internal/accounts/{accountId}/contact", accountId.value)
                .accept(MediaType.APPLICATION_JSON)
                .headers { if (CORRELATION_ID.matches(correlationId)) it.set(CORRELATION_HEADER, correlationId) }
                .awaitBodyOrProblem<AccountContactResponse>()
                .fold(
                    { error ->
                        if (error.status == NOT_FOUND) {
                            ContactLookup.Unknown
                        } else {
                            log.warn("Identity answered {} for the contact of account {}", error.status, accountId)
                            ContactLookup.Unavailable
                        }
                    },
                    { ContactLookup.Found(it.toContact()) },
                )
        } catch (failure: WebClientException) {
            log.warn(
                "Identity is unreachable for the contact of account {}: {}",
                accountId,
                failure.javaClass.simpleName,
            )
            ContactLookup.Unavailable
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(IdentityContactClient::class.java)
    }
}

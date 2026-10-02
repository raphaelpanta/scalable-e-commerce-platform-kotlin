package com.ecommerce.order.infrastructure.clients

import com.ecommerce.order.application.AccountPort
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.AddressId
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.Recipient
import com.ecommerce.platform.http.awaitBodyOrProblem
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

/** `PostalAddress` of identity-internal.yaml (personal data, never logged). */
data class PostalAddressJson(
    val recipientName: String,
    val line1: String,
    val line2: String? = null,
    val city: String,
    val region: String? = null,
    val postalCode: String,
    val countryCode: String,
) {
    fun toAddress(): DeliveryAddress =
        DeliveryAddress(recipientName, line1, line2, city, region, postalCode, countryCode)
}

/** `AccountContact` (personal data, never logged). */
data class AccountContactJson(
    val email: String,
    val phoneNumber: String? = null,
    val channels: List<String> = emptyList(),
    val anonymised: Boolean = false,
) {
    /** The recipient snapshot of the order events; the phone only when SMS is a permitted channel. */
    fun toRecipient(): Recipient {
        val permitted = channels.mapNotNull(Channel::fromWire)
        return Recipient.of(email, phoneNumber.takeIf { Channel.SMS in permitted }, permitted)
    }
}

/**
 * [AccountPort] over identity-internal.yaml: the delivery address of the checkout (`getAccountAddress`) and the
 * contact snapshot the order events carry for notification (`getAccountContact`). Unknown, foreign and anonymised
 * resources are absent.
 */
class AddressClient(
    private val client: WebClient,
) : AccountPort {
    override suspend fun address(
        accountId: AccountId,
        addressId: AddressId,
    ): DeliveryAddress? =
        required(SERVICE) {
            client
                .get()
                .uri("/internal/accounts/{accountId}/addresses/{addressId}", accountId.value, addressId.value)
                .accept(MediaType.APPLICATION_JSON)
                .awaitBodyOrProblem<PostalAddressJson>()
        }.fold(
            { absentIfNotFound(SERVICE, it) },
            { it.toAddress() },
        )

    override suspend fun recipient(accountId: AccountId): Recipient? =
        required(SERVICE) {
            client
                .get()
                .uri("/internal/accounts/{accountId}/contact", accountId.value)
                .accept(MediaType.APPLICATION_JSON)
                .awaitBodyOrProblem<AccountContactJson>()
        }.fold(
            { absentIfNotFound(SERVICE, it) },
            { contact -> contact.takeUnless { it.anonymised }?.toRecipient() },
        )

    private companion object {
        const val SERVICE = "identity"
    }
}

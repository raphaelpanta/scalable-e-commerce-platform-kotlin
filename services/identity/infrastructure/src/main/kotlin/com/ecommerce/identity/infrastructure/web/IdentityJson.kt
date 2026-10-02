package com.ecommerce.identity.infrastructure.web

import com.ecommerce.identity.application.AddressPage
import com.ecommerce.identity.application.TokenPair
import com.ecommerce.identity.domain.Account
import com.ecommerce.identity.domain.AccountContact
import com.ecommerce.identity.domain.Address
import com.ecommerce.identity.domain.NotificationPreference
import com.ecommerce.identity.domain.PostalAddress
import com.fasterxml.jackson.annotation.JsonProperty

// Request bodies of identity.yaml. Every field is nullable so that a missing one is answered with a precise 400;
// the classes holding secrets hide them from toString().

/** `RegisterRequest`. */
data class RegisterRequest(
    val email: String? = null,
    val password: String? = null,
    val displayName: String? = null,
) {
    override fun toString(): String = "RegisterRequest(****)"
}

/** `TokenRequest`. */
data class TokenRequest(
    val token: String? = null,
) {
    override fun toString(): String = "TokenRequest(****)"
}

/** `SignInRequest`. */
data class SignInRequest(
    val email: String? = null,
    val password: String? = null,
) {
    override fun toString(): String = "SignInRequest(****)"
}

/** `RefreshRequest`. */
data class RefreshRequest(
    val refreshToken: String? = null,
) {
    override fun toString(): String = "RefreshRequest(****)"
}

/** `PasswordResetRequest`. */
data class PasswordResetRequest(
    val email: String? = null,
) {
    override fun toString(): String = "PasswordResetRequest(****)"
}

/** `PasswordResetCompletion`. */
data class PasswordResetCompletion(
    val token: String? = null,
    val newPassword: String? = null,
) {
    override fun toString(): String = "PasswordResetCompletion(****)"
}

/** `ProfileUpdate`. */
data class ProfileUpdate(
    val displayName: String? = null,
)

/** `AddressInput`. */
data class AddressInputJson(
    val label: String? = null,
    val recipientName: String? = null,
    val line1: String? = null,
    val line2: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
    @param:JsonProperty("isDefault") val isDefault: Boolean? = null,
) {
    override fun toString(): String = "AddressInputJson(countryCode=$countryCode)"
}

/** `NotificationPreferencesUpdate`. */
data class PreferencesUpdate(
    val channels: List<String>? = null,
)

/** `PhoneVerificationRequest`. */
data class PhoneVerificationRequest(
    val phoneNumber: String? = null,
) {
    override fun toString(): String = "PhoneVerificationRequest(****)"
}

/** `PhoneVerificationConfirmation`. */
data class PhoneVerificationConfirmation(
    val code: String? = null,
) {
    override fun toString(): String = "PhoneVerificationConfirmation(****)"
}

/** Response bodies, as JSON members in contract order; absent optional members are omitted, not null. */
object IdentityJson {
    const val REGISTRATION_ACCEPTED: String = "If the address can be registered, a verification message has been sent."
    const val RESET_ACCEPTED: String = "If the address is registered, a reset message has been sent."

    fun message(text: String): Map<String, Any> = mapOf("message" to text)

    /** `TokenPair`. */
    fun tokens(pair: TokenPair): Map<String, Any> =
        mapOf(
            "accessToken" to pair.accessToken.value,
            "refreshToken" to pair.refreshToken.value,
            "tokenType" to "Bearer",
            "expiresIn" to pair.accessToken.expiresIn.seconds,
        )

    /** `Account`. */
    fun account(account: Account): Map<String, Any> =
        buildMap {
            put("id", account.id.value.toString())
            put("email", account.email.value)
            account.displayName?.let { put("displayName", it.value) }
            put("emailVerified", account.emailVerified)
            put("roles", account.roles.sortedBy { it.ordinal }.map { it.code })
            put("createdAt", account.createdAt.toString())
        }

    /** `Address`. */
    fun address(address: Address): Map<String, Any> =
        buildMap {
            put("id", address.id.value.toString())
            address.label?.let { put("label", it) }
            putAll(postal(address.postal))
            put("isDefault", address.isDefault)
        }

    /** `AddressPage`. */
    fun addressPage(
        page: AddressPage,
        number: Int,
        size: Int,
    ): Map<String, Any> =
        mapOf("items" to page.items.map(::address), "page" to number, "size" to size, "totalItems" to page.totalItems)

    /** `PostalAddress` of identity-internal.yaml (and the postal members of `Address`). */
    fun postal(postal: PostalAddress): Map<String, Any> =
        buildMap {
            put("recipientName", postal.recipientName)
            put("line1", postal.line1)
            postal.line2?.let { put("line2", it) }
            put("city", postal.city)
            postal.region?.let { put("region", it) }
            put("postalCode", postal.postalCode)
            put("countryCode", postal.countryCode)
        }

    /** `NotificationPreferences`. */
    fun preferences(preference: NotificationPreference): Map<String, Any> =
        buildMap {
            put("channels", preference.channels.sortedBy { it.ordinal }.map { it.code })
            preference.phone?.let { put("phoneNumber", it.value) }
            put("phoneVerified", preference.phoneVerified)
        }

    /** `AccountContact` of identity-internal.yaml. */
    fun contact(contact: AccountContact): Map<String, Any> =
        buildMap {
            put("accountId", contact.accountId.value.toString())
            put("email", contact.email.value)
            contact.phone?.let { put("phoneNumber", it.value) }
            put("phoneVerified", contact.phoneVerified)
            put("channels", contact.channels.map { it.code })
            put("anonymised", contact.anonymised)
        }
}

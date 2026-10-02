package com.ecommerce.platform.core.values

import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.result.Validated
import com.ecommerce.platform.core.result.ValidatedAll
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.core.result.validateAll
import com.ecommerce.platform.observability.Pii
import com.ecommerce.platform.observability.PiiMasking
import java.util.Locale

/**
 * A delivery address (data-model section 2). Text fields are trimmed; optional fields ([line2], [region]) are
 * `null` when blank; [countryCode] is an upper-cased ISO-3166 alpha-2 code. Personal data: `toString()` masks
 * every field but the country.
 */
@Pii
@ConsistentCopyVisibility
data class PostalAddress private constructor(
    val recipientName: String,
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String?,
    val postalCode: String,
    val countryCode: String,
) {
    override fun toString(): String =
        "PostalAddress(recipientName=${PiiMasking.mask(recipientName)}, line1=${PiiMasking.MASK}, " +
            "line2=${line2?.let { PiiMasking.MASK }}, city=${PiiMasking.mask(city)}, " +
            "region=${PiiMasking.mask(region)}, postalCode=${PiiMasking.MASK}, countryCode=$countryCode)"

    companion object {
        const val RECIPIENT_NAME_MAX: Int = 100
        const val LINE_MAX: Int = 120
        const val CITY_MAX: Int = 80
        const val REGION_MAX: Int = 80
        const val POSTAL_CODE_MAX: Int = 20
        private val COUNTRIES: Set<String> = Locale.getISOCountries().toSet()

        /** Validates every field and reports all broken rules at once (a form shows them together). */
        @Suppress("LongParameterList") // one parameter per address field, as in the API contracts
        fun of(
            recipientName: String,
            line1: String,
            line2: String?,
            city: String,
            region: String?,
            postalCode: String,
            countryCode: String,
        ): ValidatedAll<PostalAddress> =
            validateAll(
                TextRules.trimmedLength(recipientName, "recipientName", 1, RECIPIENT_NAME_MAX),
                TextRules.trimmedLength(line1, "line1", 1, LINE_MAX),
                TextRules.optionalTrimmedLength(line2, "line2", LINE_MAX),
                TextRules.trimmedLength(city, "city", 1, CITY_MAX),
                TextRules.optionalTrimmedLength(region, "region", REGION_MAX),
                TextRules.trimmedLength(postalCode, "postalCode", 1, POSTAL_CODE_MAX),
                country(countryCode),
                ::PostalAddress,
            )

        private fun country(raw: String): Validated<String> {
            val code = raw.trim().uppercase(Locale.ROOT)
            return if (code in COUNTRIES) {
                code.right()
            } else {
                ValidationError("countryCode", "must be an ISO-3166 alpha-2 country code").left()
            }
        }
    }
}

package com.ecommerce.identity.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.Locale

/**
 * A postal address (data-model section 2 `PostalAddress`): recipientName 1-100, line1 1-120, line2 0-120, city
 * 1-80, region 0-80, postalCode 1-20 characters (trimmed; blank optional fields are `null`) and an ISO-3166 alpha-2
 * [countryCode]. Personal data: `toString()` shows the country only.
 */
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
    override fun toString(): String = "PostalAddress(countryCode=$countryCode)"

    /** The raw fields of an address input, validated together by [validate]. */
    data class Fields(
        val recipientName: String?,
        val line1: String?,
        val line2: String?,
        val city: String?,
        val region: String?,
        val postalCode: String?,
        val countryCode: String?,
    ) {
        override fun toString(): String = "PostalAddress.Fields(countryCode=$countryCode)"
    }

    companion object {
        const val RECIPIENT_NAME_MAX: Int = 100
        const val LINE_MAX: Int = 120
        const val CITY_MAX: Int = 80
        const val REGION_MAX: Int = 80
        const val POSTAL_CODE_MAX: Int = 20
        private val COUNTRIES: Set<String> = Locale.getISOCountries().toSet()

        /** Validates every field and reports every broken rule at once. */
        fun validate(fields: Fields): Either<List<FieldError>, PostalAddress> {
            val recipientName = required(fields.recipientName, "recipientName", RECIPIENT_NAME_MAX)
            val line1 = required(fields.line1, "line1", LINE_MAX)
            val line2 = optional(fields.line2, "line2", LINE_MAX)
            val city = required(fields.city, "city", CITY_MAX)
            val region = optional(fields.region, "region", REGION_MAX)
            val postalCode = required(fields.postalCode, "postalCode", POSTAL_CODE_MAX)
            val country = country(fields.countryCode)
            val errors =
                listOf(recipientName, line1, line2, city, region, postalCode, country).mapNotNull { it.leftOrNull() }
            return if (errors.isEmpty()) {
                PostalAddress(
                    recipientName.value(),
                    line1.value(),
                    line2.getOrNull(),
                    city.value(),
                    region.getOrNull(),
                    postalCode.value(),
                    country.value(),
                ).right()
            } else {
                errors.left()
            }
        }

        private fun Either<FieldError, String>.value(): String = fold({ error(it.reason) }, { it })

        private fun required(
            raw: String?,
            field: String,
            max: Int,
        ): Either<FieldError, String> {
            val value = raw?.trim().orEmpty()
            return when {
                value.isEmpty() -> FieldError(field, "must not be blank").left()
                value.length > max -> FieldError(field, "must be at most $max characters").left()
                else -> value.right()
            }
        }

        private fun optional(
            raw: String?,
            field: String,
            max: Int,
        ): Either<FieldError, String?> {
            val value = raw?.trim().orEmpty()
            return when {
                value.isEmpty() -> null.right()
                value.length > max -> FieldError(field, "must be at most $max characters").left()
                else -> value.right()
            }
        }

        private fun country(raw: String?): Either<FieldError, String> {
            val code = raw?.trim().orEmpty()
            return if (code in COUNTRIES) {
                code.right()
            } else {
                FieldError("countryCode", "must be an ISO-3166 alpha-2 country code").left()
            }
        }
    }
}

/** A validated address input: an optional [label] (at most 50 characters), the postal fields and the default flag. */
data class AddressDraft(
    val label: String?,
    val postal: PostalAddress,
    val isDefault: Boolean,
) {
    override fun toString(): String = "AddressDraft(postal=$postal, isDefault=$isDefault)"

    companion object {
        const val LABEL_MAX: Int = 50

        fun of(
            label: String?,
            fields: PostalAddress.Fields,
            isDefault: Boolean,
        ): Either<IdentityError, AddressDraft> {
            val trimmed = label?.trim()?.takeIf { it.isNotEmpty() }
            val labelError =
                if (trimmed != null && trimmed.length > LABEL_MAX) {
                    FieldError("label", "must be at most $LABEL_MAX characters")
                } else {
                    null
                }
            val postal = PostalAddress.validate(fields)
            val address = postal.getOrNull()
            return if (address == null || labelError != null) {
                IdentityError.Invalid(listOfNotNull(labelError) + postal.leftOrNull().orEmpty()).left()
            } else {
                AddressDraft(trimmed, address, isDefault).right()
            }
        }
    }
}

/** A delivery address of an account (data-model section 3.1). */
data class Address(
    val id: AddressId,
    val label: String?,
    val postal: PostalAddress,
    val isDefault: Boolean,
)

/**
 * The delivery addresses of one account, in creation order: at most [MAX_ADDRESSES], at most one default (a new
 * default takes the flag from the previous one; removing the default promotes none). Addresses are edited only by
 * their owner, so an id of another book is simply not found.
 */
data class AddressBook(
    val accountId: AccountId,
    val addresses: List<Address>,
) {
    init {
        require(addresses.count { it.isDefault } <= 1) { "at most one default address" }
    }

    fun find(id: AddressId): Address? = addresses.firstOrNull { it.id == id }

    fun add(
        id: AddressId,
        draft: AddressDraft,
    ): Either<IdentityError, AddressBook> =
        if (addresses.size >= MAX_ADDRESSES) {
            IdentityError.TooManyAddresses.left()
        } else {
            withDefault(
                addresses + Address(id, draft.label, draft.postal, draft.isDefault),
                id,
                draft.isDefault,
            ).right()
        }

    fun replace(
        id: AddressId,
        draft: AddressDraft,
    ): Either<IdentityError, AddressBook> =
        if (find(id) == null) {
            IdentityError.AddressNotFound.left()
        } else {
            val replaced =
                addresses.map {
                    if (it.id ==
                        id
                    ) {
                        Address(id, draft.label, draft.postal, draft.isDefault)
                    } else {
                        it
                    }
                }
            withDefault(replaced, id, draft.isDefault).right()
        }

    fun remove(id: AddressId): Either<IdentityError, AddressBook> =
        if (find(id) ==
            null
        ) {
            IdentityError.AddressNotFound.left()
        } else {
            copy(addresses = addresses.filterNot { it.id == id }).right()
        }

    /** Every address removed (account deletion). */
    fun cleared(): AddressBook = copy(addresses = emptyList())

    /** The addresses of one page: [limit] addresses after the first [offset]. */
    fun page(
        offset: Long,
        limit: Int,
    ): List<Address> = addresses.drop(offset.coerceAtMost(addresses.size.toLong()).toInt()).take(limit)

    private fun withDefault(
        candidates: List<Address>,
        id: AddressId,
        isDefault: Boolean,
    ): AddressBook =
        copy(addresses = if (isDefault) candidates.map { it.copy(isDefault = it.id == id) } else candidates)

    companion object {
        const val MAX_ADDRESSES: Int = 10

        fun empty(accountId: AccountId): AddressBook = AddressBook(accountId, emptyList())
    }
}

package com.ecommerce.platform.testing

import arrow.core.Either
import com.ecommerce.platform.core.values.CorrelationId
import com.ecommerce.platform.core.values.Currency
import com.ecommerce.platform.core.values.Email
import com.ecommerce.platform.core.values.IdempotencyKey
import com.ecommerce.platform.core.values.Money
import com.ecommerce.platform.core.values.PageRequest
import com.ecommerce.platform.core.values.PhoneNumber
import com.ecommerce.platform.core.values.PostalAddress
import com.ecommerce.platform.core.values.Quantity
import com.ecommerce.platform.core.values.SecretToken
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import java.util.Locale

/**
 * Kotest generators of valid shared value objects (and of their raw text), for property tests in every service:
 * `checkAll(PlatformArbs.email()) { email -> ... }`.
 */
@Suppress("TooManyFunctions") // one generator (and its raw-text form) per shared value object
object PlatformArbs {
    private val LOWER = ('a'..'z').toList()
    private val DIGITS = ('0'..'9').toList()
    private val ALPHANUMERIC = LOWER + ('A'..'Z') + DIGITS
    private val VISIBLE_ASCII = ('!'..'~').toList()
    private val NAME_CHARS = ALPHANUMERIC + listOf(' ', '-', '\'', '.', 'ã', 'é', 'ç')
    private val COUNTRIES = Locale.getISOCountries().toList()
    private const val MAX_TEST_AMOUNT = 1_000_000_000L
    private const val MAX_TEST_PAGE = 10_000
    private const val LOCAL_MAX = 64
    private const val HOST_MAX = 30
    private const val TLD_MAX = 6
    private const val LAST_DIGIT = 9
    private const val PHONE_MIN_REST = 7
    private const val PHONE_MAX_REST = 14

    /** A string of [min]..[max] characters drawn from [chars]. */
    fun text(
        chars: List<Char>,
        min: Int,
        max: Int,
    ): Arb<String> = Arb.list(Arb.element(chars), min..max).map { it.joinToString("") }

    /** Raw email text accepted by [Email.of] (lower case; local part up to 64, domain with a dot). */
    fun emailText(): Arb<String> =
        Arb.bind(
            text(ALPHANUMERIC, 1, LOCAL_MAX),
            text(LOWER, 1, HOST_MAX),
            text(LOWER, 2, TLD_MAX),
        ) { local, host, tld ->
            "$local@$host.$tld"
        }

    fun email(): Arb<Email> = emailText().map { Email.of(it).valid() }

    /** Raw E.164 text: `+`, a non-zero digit and 7..14 more digits. */
    fun phoneText(): Arb<String> =
        Arb.bind(Arb.int(1..LAST_DIGIT), text(DIGITS, PHONE_MIN_REST, PHONE_MAX_REST)) { first, rest -> "+$first$rest" }

    fun phoneNumber(): Arb<PhoneNumber> = phoneText().map { PhoneNumber.of(it).valid() }

    fun quantity(): Arb<Quantity> = Arb.int(Quantity.MIN..Quantity.MAX).map { Quantity.of(it).valid() }

    fun currency(): Arb<Currency> = Arb.constant(Currency.BRL)

    /** Non-negative amounts small enough that sums and products of a few of them never overflow. */
    fun money(currency: Currency = Currency.BRL): Arb<Money> =
        Arb.long(0L..MAX_TEST_AMOUNT).map { Money.of(it, currency).valid() }

    fun postalAddress(): Arb<PostalAddress> =
        Arb.bind(
            text(NAME_CHARS, 1, PostalAddress.RECIPIENT_NAME_MAX).map { "A$it".take(PostalAddress.RECIPIENT_NAME_MAX) },
            text(ALPHANUMERIC, 1, PostalAddress.LINE_MAX),
            text(ALPHANUMERIC, 1, PostalAddress.LINE_MAX).orNull(),
            text(ALPHANUMERIC, 1, PostalAddress.CITY_MAX),
            text(ALPHANUMERIC, 1, PostalAddress.REGION_MAX).orNull(),
            text(ALPHANUMERIC, 1, PostalAddress.POSTAL_CODE_MAX),
            Arb.element(COUNTRIES),
        ) { name, line1, line2, city, region, postalCode, country ->
            PostalAddress.of(name, line1, line2, city, region, postalCode, country).valid()
        }

    /** Raw correlation id text accepted by [CorrelationId.of]. */
    fun correlationIdText(): Arb<String> = text(ALPHANUMERIC + '-', 1, CorrelationId.MAX_LENGTH)

    fun correlationId(): Arb<CorrelationId> = correlationIdText().map { CorrelationId.of(it).valid() }

    /** Raw idempotency key text accepted by [IdempotencyKey.of]. */
    fun idempotencyKeyText(): Arb<String> = text(VISIBLE_ASCII, 1, IdempotencyKey.MAX_LENGTH)

    fun idempotencyKey(): Arb<IdempotencyKey> = idempotencyKeyText().map { IdempotencyKey.of(it).valid() }

    fun secretToken(): Arb<SecretToken> = Arb.int().map { SecretToken.generate() }

    fun pageRequest(): Arb<PageRequest> =
        Arb.bind(
            Arb.int(0..MAX_TEST_PAGE),
            Arb.int(1..PageRequest.MAX_SIZE),
        ) { page, size -> PageRequest.of(page, size).valid() }

    private fun <E, T : Any> Either<E, T>.valid(): T = getOrNull() ?: error("generator produced $this")
}

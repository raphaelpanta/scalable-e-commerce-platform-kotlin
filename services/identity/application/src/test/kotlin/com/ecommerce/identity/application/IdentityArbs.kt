package com.ecommerce.identity.application

import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AddressDraft
import com.ecommerce.identity.domain.Password
import com.ecommerce.identity.domain.PostalAddress
import com.ecommerce.identity.domain.ThrottlePolicy
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uuid
import java.time.Duration
import java.time.Instant

/**
 * Generators of the identity use-case property specs (constitution Principle V: fixtures come from generators). The
 * use cases run on the in-memory ports of `Fakes.kt`, so a property costs microseconds; [PROPERTIES] keeps the
 * iteration count moderate because Pitest runs every spec once per mutant.
 */
object IdentityArbs {
    @OptIn(ExperimentalKotest::class)
    val PROPERTIES: PropTestConfig = PropTestConfig(iterations = 100)

    private val LOWER = ('a'..'z').toList()
    private val ALPHANUMERIC = LOWER + ('A'..'Z') + ('0'..'9')
    private const val MAX_LOCAL = 20
    private const val MAX_HOST = 12
    private const val PASSWORD_MAX_EXTRA = 30
    private const val MAX_FAILURES = 8
    private const val MAX_LOCK_SECONDS = 4 * 3600L
    private const val OCTET = 255
    private const val CODE_DIGITS = 6
    private const val YEAR_SECONDS = 365L * 24 * 3600

    fun text(
        chars: List<Char>,
        min: Int,
        max: Int,
    ): Arb<String> = Arb.list(Arb.element(chars), min..max).map { it.joinToString("") }

    /** A valid email address as a shopper types it (lower case, so it is its own normal form). */
    val email: Arb<String> =
        Arb.bind(text(LOWER, 1, MAX_LOCAL), text(LOWER, 1, MAX_HOST)) { local, host -> "$local@$host.test" }

    /** A password that meets the policy (12..128 characters) and differs from any generated email. */
    val password: Arb<String> =
        text(ALPHANUMERIC, Password.MIN_LENGTH, Password.MIN_LENGTH + PASSWORD_MAX_EXTRA).map { "P-$it" }

    /** A source address of the documentation range 198.51.100.0/24. */
    val source: Arb<String> = Arb.int(0..OCTET).map { "198.51.100.$it" }

    val accountId: Arb<AccountId> = Arb.uuid().map(::AccountId)

    /** A sign-in throttle policy: 1..8 failures and a lock of one second to four hours. */
    val throttlePolicy: Arb<ThrottlePolicy> =
        Arb.bind(Arb.int(1..MAX_FAILURES), Arb.long(1L..MAX_LOCK_SECONDS)) { failures, seconds ->
            ThrottlePolicy(failures, Duration.ofSeconds(seconds))
        }

    /** A non-negative duration of at most [maxSeconds]. */
    fun duration(maxSeconds: Long): Arb<Duration> = Arb.long(0L..maxSeconds).map(Duration::ofSeconds)

    /** An instant within about a year of [NOW]. */
    val instant: Arb<Instant> = Arb.long(-YEAR_SECONDS..YEAR_SECONDS).map { NOW.plusSeconds(it) }

    /** A valid delivery address draft. */
    val addressDraft: Arb<AddressDraft> =
        Arb.bind(text(LOWER, 1, MAX_HOST), text(LOWER, 1, MAX_HOST), Arb.boolean()) { name, city, isDefault ->
            AddressDraft
                .of(
                    name,
                    PostalAddress.Fields(name, "Rua $name 1", null, city, null, "1000-001", "PT"),
                    isDefault,
                ).value()
        }

    /** A Brazilian mobile number in E.164 form. */
    val phone: Arb<String> = text(('0'..'9').toList(), CODE_DIGITS + 2, CODE_DIGITS + 2).map { "+55119$it" }
}

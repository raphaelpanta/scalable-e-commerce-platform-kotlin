package com.ecommerce.identity.infrastructure.security

import com.ecommerce.identity.application.Clock
import com.ecommerce.identity.application.Secrets
import com.ecommerce.identity.domain.OpaqueToken
import com.ecommerce.identity.domain.VerificationCode
import com.ecommerce.platform.core.values.SecretToken
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Outbound adapter: 256-bit url-safe tokens and 6-digit codes from a [SecureRandom], random (v4) ids. */
class SecureSecrets(
    private val random: SecureRandom = SecureRandom(),
) : Secrets {
    override fun opaqueToken(): OpaqueToken =
        OpaqueToken.of(SecretToken.generate(random).value).fold({ error("generated token: ${it.reason}") }, { it })

    override fun verificationCode(): VerificationCode =
        VerificationCode
            .of(random.nextInt(CODE_BOUND).toString().padStart(VerificationCode.DIGITS, '0'))
            .fold({ error("generated code: ${it.reason}") }, { it })

    override fun newId(): UUID = UUID.randomUUID()

    private companion object {
        const val CODE_BOUND = 1_000_000
    }
}

/** Outbound adapter: the time of [clock], truncated to microseconds (the precision PostgreSQL keeps). */
class SystemClock(
    private val clock: java.time.Clock,
) : Clock {
    override fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

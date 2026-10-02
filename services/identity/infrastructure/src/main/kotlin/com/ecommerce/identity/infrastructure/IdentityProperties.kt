package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.application.IdentityPolicies
import com.ecommerce.identity.domain.ThrottlePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `identity.*` (application.yml). */
@ConfigurationProperties("identity")
data class IdentityProperties(
    /**
     * Ed25519 private key that signs access tokens, PKCS#8 as PEM or bare Base64 (`IDENTITY_SIGNING_KEY`). Blank:
     * a key pair is generated at start-up (fine for one instance; several instances must share a key).
     */
    val signingKey: String = "",
    /** `kid` of the signing key; blank: the RFC 7638 thumbprint of its public key (stable for a given key). */
    val signingKeyId: String = "",
    val accessTokenLifetime: Duration = Duration.ofMinutes(DEFAULT_ACCESS_MINUTES),
    val sessionLifetime: Duration = Duration.ofDays(DEFAULT_SESSION_DAYS),
    val signIn: SignIn = SignIn(),
    val sms: Sms = Sms(),
) {
    /** `identity.sign-in.*`: consecutive failed sign-ins before a lock, per account and per source address. */
    data class SignIn(
        val accountMaxFailures: Int = ThrottlePolicy.DEFAULT_MAX_FAILURES,
        val accountLock: Duration = ThrottlePolicy.DEFAULT_LOCK,
        /** Several people (or the whole test suite) may share one address, so its limit is configurable. */
        val sourceMaxFailures: Int = DEFAULT_SOURCE_MAX_FAILURES,
        val sourceLock: Duration = ThrottlePolicy.DEFAULT_LOCK,
    )

    /** `identity.sms.*`: the sender of the Mailpit mirror of simulated SMS. */
    data class Sms(
        val from: String = "sms@ecommerce.example",
    )

    fun policies(): IdentityPolicies =
        IdentityPolicies(
            accountThrottle = ThrottlePolicy(signIn.accountMaxFailures, signIn.accountLock),
            sourceThrottle = ThrottlePolicy(signIn.sourceMaxFailures, signIn.sourceLock),
            accessTokenLifetime = accessTokenLifetime,
            sessionLifetime = sessionLifetime,
        )

    private companion object {
        const val DEFAULT_ACCESS_MINUTES = 15L
        const val DEFAULT_SESSION_DAYS = 30L
        const val DEFAULT_SOURCE_MAX_FAILURES = 20
    }
}

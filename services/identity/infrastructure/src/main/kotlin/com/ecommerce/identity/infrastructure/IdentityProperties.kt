package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.application.IdentityPolicies
import com.ecommerce.identity.application.RetentionPolicy
import com.ecommerce.identity.domain.ThrottlePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `identity.*` (application.yml). */
@ConfigurationProperties("identity")
data class IdentityProperties(
    /**
     * Ed25519 private key that signs access tokens, PKCS#8 as PEM or bare Base64 (`IDENTITY_SIGNING_KEY`), shared by
     * every instance (FR-024). Blank is refused at start-up unless the profile `dev` or `test` is active, which
     * generates a throw-away key pair instead.
     */
    val signingKey: String = "",
    /** `kid` of the signing key; blank: the RFC 7638 thumbprint of its public key (stable for a given key). */
    val signingKeyId: String = "",
    val accessTokenLifetime: Duration = Duration.ofMinutes(DEFAULT_ACCESS_MINUTES),
    val sessionLifetime: Duration = Duration.ofDays(DEFAULT_SESSION_DAYS),
    val signIn: SignIn = SignIn(),
    val sms: Sms = Sms(),
    val retention: Retention = Retention(),
) {
    /** `identity.sign-in.*`: consecutive failed sign-ins before a lock, per account and per source address. */
    data class SignIn(
        val accountMaxFailures: Int = ThrottlePolicy.DEFAULT_MAX_FAILURES,
        val accountLock: Duration = ThrottlePolicy.DEFAULT_LOCK,
        /** FR-006's 5 by default; configurable because several people (a NAT, a test runner) may share one address. */
        val sourceMaxFailures: Int = ThrottlePolicy.DEFAULT_MAX_FAILURES,
        val sourceLock: Duration = ThrottlePolicy.DEFAULT_LOCK,
    )

    /** `identity.sms.*`: the sender of the Mailpit mirror of simulated SMS. */
    data class Sms(
        val from: String = "sms@ecommerce.example",
    )

    /**
     * `identity.retention.*` (data-model section 5): how long expired or ended records are kept, and how often the
     * purge runs.
     */
    data class Retention(
        val tokens: Duration = RetentionPolicy().tokenRetention,
        val sessions: Duration = RetentionPolicy().sessionRetention,
        val throttleIdle: Duration = RetentionPolicy().throttleIdle,
        val phoneVerifications: Duration = RetentionPolicy().phoneVerificationRetention,
        val purgeInterval: Duration = Duration.ofHours(1),
    ) {
        fun policy(): RetentionPolicy = RetentionPolicy(tokens, sessions, throttleIdle, phoneVerifications)
    }

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
    }
}

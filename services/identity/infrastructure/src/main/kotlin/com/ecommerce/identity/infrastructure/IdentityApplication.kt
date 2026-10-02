package com.ecommerce.identity.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Service for the bounded context "identity" (user story 3, FR-004..FR-007): accounts, sessions and access tokens
 * (EdDSA JWTs and their JWKS), password reset, profile, addresses, notification preferences and phone verification,
 * the internal address and contact lookups, and the account events.
 */
@SpringBootApplication
class IdentityApplication

/** `SEED=true` activates the Spring profile `seed`: Flyway also runs `classpath:db/seed` (service conventions). */
object SeedProfile {
    const val NAME: String = "seed"

    /** The additional profiles for the value of the `SEED` environment variable. */
    fun profilesFor(seed: String?): Array<String> = if (seed.toBoolean()) arrayOf(NAME) else emptyArray()
}

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments and profiles are copied once, at boot
    runApplication<IdentityApplication>(*args) {
        setAdditionalProfiles(*SeedProfile.profilesFor(System.getenv("SEED")))
    }
}

package com.ecommerce.platform.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpMethod
import java.net.URI
import java.time.Duration

/**
 * `platform.security.*`. The defaults of the deployment-specific values are mapped from the environment by the
 * platform defaults (`JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`, `INTERNAL_API_TOKEN`; see
 * `PlatformDefaultsEnvironmentPostProcessor`), so a service only declares its [publicPaths].
 */
@ConfigurationProperties("platform.security")
class PlatformSecurityProperties {
    /** Switches the platform security chain off (a module that brings its own `SecurityWebFilterChain`). */
    var enabled: Boolean = true

    /** JWKS document of the identity service. */
    var jwksUri: URI = URI.create("http://localhost:8080/.well-known/jwks.json")

    /** Required `iss` claim. */
    var issuer: String = "https://identity.ecommerce.local"

    /** Required `aud` claim. */
    var audience: String = "ecommerce-api"

    /**
     * Routes reachable without a token, as `METHOD pattern` or `pattern` (any method) in Spring path-pattern syntax,
     * for example `GET /api/v1/catalog/{*path}`. A token that is present on such a route is still validated.
     */
    var publicPaths: List<String> = emptyList()

    /** Shared secret expected in `X-Internal-Token` on the internal endpoints; blank rejects every internal call. */
    var internalToken: String = ""

    /** How long fetched signing keys are reused before the JWKS is fetched again. */
    var jwksCacheTtl: Duration = Duration.ofMinutes(DEFAULT_JWKS_TTL_MINUTES)

    /** Browser origins allowed by CORS; empty disables cross-origin access. */
    var cors: Cors = Cors()

    /** CORS allow-list. */
    class Cors {
        var allowedOrigins: List<String> = emptyList()
    }

    /** [publicPaths] parsed into method and pattern. */
    val parsedPublicPaths: List<PublicPath> get() = publicPaths.map(PublicPath::parse)

    /** One public route: [method] `null` matches every method. */
    data class PublicPath(
        val method: HttpMethod?,
        val pattern: String,
    ) {
        companion object {
            /** Parses `GET /api/v1/catalog/{*path}` (one method) or `/api/v1/catalog/{*path}` (every method). */
            fun parse(value: String): PublicPath {
                val parts = value.trim().split(Regex("\\s+"), limit = 2)
                return if (parts.size == 2) {
                    PublicPath(HttpMethod.valueOf(parts[0].uppercase()), parts[1])
                } else {
                    PublicPath(null, parts[0])
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_JWKS_TTL_MINUTES = 5L
    }
}

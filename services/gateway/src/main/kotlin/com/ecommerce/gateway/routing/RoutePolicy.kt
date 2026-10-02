package com.ecommerce.gateway.routing

import org.springframework.util.unit.DataSize
import java.time.Duration

private const val AUTH_PER_MINUTE = 10
private const val BROWSE_PER_MINUTE = 600
private const val STANDARD_PER_MINUTE = 120
private const val CHECKOUT_PER_MINUTE = 20
private const val OPERATOR_PER_MINUTE = 300
private const val SHORT_TIMEOUT_SECONDS = 5L
private const val STANDARD_TIMEOUT_SECONDS = 10L
private const val OPERATOR_TIMEOUT_SECONDS = 15L
private const val CHECKOUT_TIMEOUT_SECONDS = 30L

/** How a tier identifies the client whose budget a request consumes. */
enum class ClientKey {
    /** Always the source address, also for authenticated calls (credential stuffing protection). */
    SOURCE_ADDRESS,

    /** The account id of an authenticated caller, otherwise the source address. */
    ACCOUNT_OR_ADDRESS,
}

/**
 * Rate-limit tiers of contracts/gateway-routes.md. The limits are defaults (overridable under
 * `gateway.rate-limit.requests-per-minute`); [upstreamTimeout] is the `response-timeout` every route of the tier
 * declares in its metadata (checked at start-up by [RoutePolicies]).
 */
enum class Tier(
    val id: String,
    val defaultRequestsPerMinute: Int,
    val clientKey: ClientKey,
    val upstreamTimeout: Duration,
) {
    AUTH("auth", AUTH_PER_MINUTE, ClientKey.SOURCE_ADDRESS, Duration.ofSeconds(SHORT_TIMEOUT_SECONDS)),
    BROWSE("browse", BROWSE_PER_MINUTE, ClientKey.ACCOUNT_OR_ADDRESS, Duration.ofSeconds(SHORT_TIMEOUT_SECONDS)),
    STANDARD(
        "standard",
        STANDARD_PER_MINUTE,
        ClientKey.ACCOUNT_OR_ADDRESS,
        Duration.ofSeconds(STANDARD_TIMEOUT_SECONDS),
    ),
    CHECKOUT(
        "checkout",
        CHECKOUT_PER_MINUTE,
        ClientKey.ACCOUNT_OR_ADDRESS,
        Duration.ofSeconds(CHECKOUT_TIMEOUT_SECONDS),
    ),
    OPERATOR(
        "operator",
        OPERATOR_PER_MINUTE,
        ClientKey.ACCOUNT_OR_ADDRESS,
        Duration.ofSeconds(OPERATOR_TIMEOUT_SECONDS),
    ),
    ;

    companion object {
        fun of(id: String): Tier =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown rate-limit tier '$id' (expected one of ${ids()})")

        private fun ids(): String = entries.joinToString { it.id }
    }
}

/** Coarse authorisation of a route; every service repeats its own decision (deny by default, FR-005). */
enum class AuthRequirement(
    val id: String,
    private val requiredRole: String?,
) {
    /** No token required; a token that is present must still be valid (checked by the resource server). */
    ANONYMOUS("anonymous", null),

    /** A valid token with any role (`shopper` or `operator`). */
    AUTHENTICATED("authenticated", null),
    SHOPPER("shopper", Roles.SHOPPER),
    OPERATOR("operator", Roles.OPERATOR),
    ;

    /** The access decision for a caller (null when no token was presented). */
    fun decide(caller: Caller?): AccessDecision =
        when {
            this == ANONYMOUS -> AccessDecision.GRANTED
            caller == null -> AccessDecision.UNAUTHENTICATED
            requiredRole == null || requiredRole in caller.roles -> AccessDecision.GRANTED
            else -> AccessDecision.FORBIDDEN
        }

    companion object {
        fun of(id: String): AuthRequirement =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException(
                    "unknown auth requirement '$id' (expected one of ${entries.joinToString { it.id }})",
                )
    }
}

enum class AccessDecision { GRANTED, UNAUTHENTICATED, FORBIDDEN }

object Roles {
    const val SHOPPER = "shopper"
    const val OPERATOR = "operator"
}

/** The validated identity of a request: the token's `sub` and `roles`. */
data class Caller(
    val accountId: String,
    val roles: Set<String>,
)

/**
 * The policy a route declares in its `metadata` (application.yml):
 *
 * ```yaml
 * metadata:
 *   auth: anonymous | authenticated | shopper | operator
 *   tier: auth | browse | standard | checkout | operator
 *   operator-tier: operator      # optional: tier used when the caller holds the operator role
 *   max-body-size: 5MB           # optional: overrides gateway.max-body-size
 *   response-timeout: 5000       # read by Spring Cloud Gateway; must equal the tier's upstream timeout
 * ```
 */
data class RoutePolicy(
    val auth: AuthRequirement,
    val tier: Tier,
    val operatorTier: Tier? = null,
    val maxBodySize: DataSize? = null,
) {
    /** The tier whose budget this caller consumes on the route. */
    fun tierFor(caller: Caller?): Tier =
        operatorTier?.takeIf { caller != null && Roles.OPERATOR in caller.roles } ?: tier

    companion object {
        const val AUTH = "auth"
        const val TIER = "tier"
        const val OPERATOR_TIER = "operator-tier"
        const val MAX_BODY_SIZE = "max-body-size"
        const val RESPONSE_TIMEOUT = "response-timeout"

        /** Parses and validates a route's metadata; a route without an explicit auth requirement is refused. */
        fun from(
            routeId: String,
            metadata: Map<String, Any?>,
        ): RoutePolicy {
            fun required(key: String): String =
                requireNotNull(metadata[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)) {
                    "metadata '$key' is required"
                }
            try {
                val policy =
                    RoutePolicy(
                        auth = AuthRequirement.of(required(AUTH)),
                        tier = Tier.of(required(TIER)),
                        operatorTier = metadata[OPERATOR_TIER]?.toString()?.let(Tier::of),
                        maxBodySize = metadata[MAX_BODY_SIZE]?.toString()?.let(DataSize::parse),
                    )
                val timeout = Duration.ofMillis(required(RESPONSE_TIMEOUT).toLong())
                require(timeout == policy.tier.upstreamTimeout) {
                    "response-timeout ${timeout.toMillis()} differs from the ${policy.tier.id} tier's " +
                        "${policy.tier.upstreamTimeout.toMillis()} ms"
                }
                return policy
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("route '$routeId': ${invalid.message}", invalid)
            }
        }
    }
}

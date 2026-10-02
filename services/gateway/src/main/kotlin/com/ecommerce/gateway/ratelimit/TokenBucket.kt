package com.ecommerce.gateway.ratelimit

import java.time.Duration
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Token bucket of one client in one tier: [capacity] tokens (the per-minute limit), refilled continuously at
 * `capacity / period`. A full bucket allows a burst of [capacity] requests; afterwards one request per
 * `period / capacity`. Immutable: [take] returns the next state with the decision.
 */
data class TokenBucket(
    val tokens: Double,
    val updatedAtNanos: Long,
) {
    /** Takes one token at [nowNanos], refilling first. */
    fun take(
        limit: Limit,
        nowNanos: Long,
    ): Pair<TokenBucket, Decision> {
        val available = refilled(limit, nowNanos)
        return if (available >= 1.0) {
            TokenBucket(available - 1.0, nowNanos) to Decision.Allowed(remaining = (available - 1.0).toInt())
        } else {
            val missingNanos = (1.0 - available) / limit.tokensPerNano
            val retryAfterSeconds = max(1L, ceil(missingNanos / NANOS_PER_SECOND).toLong())
            TokenBucket(available, nowNanos) to Decision.Rejected(retryAfterSeconds)
        }
    }

    /** Whether the bucket has refilled completely at [nowNanos] (it can then be forgotten). */
    fun isFull(
        limit: Limit,
        nowNanos: Long,
    ): Boolean = refilled(limit, nowNanos) >= limit.capacity

    private fun refilled(
        limit: Limit,
        nowNanos: Long,
    ): Double {
        val elapsed = max(0L, nowNanos - updatedAtNanos)
        return min(limit.capacity.toDouble(), tokens + elapsed * limit.tokensPerNano)
    }

    companion object {
        fun full(
            limit: Limit,
            nowNanos: Long,
        ): TokenBucket = TokenBucket(limit.capacity.toDouble(), nowNanos)
    }

    /** [capacity] requests per [period]. */
    data class Limit(
        val capacity: Int,
        val period: Duration = Duration.ofMinutes(1),
    ) {
        init {
            require(capacity > 0) { "a rate limit must allow at least one request" }
            require(!period.isNegative && !period.isZero) { "a rate limit period must be positive" }
        }

        val tokensPerNano: Double = capacity.toDouble() / period.toNanos()
    }

    sealed interface Decision {
        data class Allowed(
            val remaining: Int,
        ) : Decision

        data class Rejected(
            val retryAfterSeconds: Long,
        ) : Decision
    }
}

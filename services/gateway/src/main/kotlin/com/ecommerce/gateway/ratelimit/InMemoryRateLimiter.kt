package com.ecommerce.gateway.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Token buckets per client key, held in this gateway instance's memory.
 *
 * MVP deviation (docs/gateway.md): limits are enforced per gateway instance, not in a shared store; with N
 * instances behind a load balancer a client may get up to N times the configured budget. Buckets that have
 * refilled completely are dropped every [SWEEP_EVERY] decisions, so idle clients cost no memory.
 */
class InMemoryRateLimiter(
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val buckets = ConcurrentHashMap<String, Entry>()
    private val decisions = AtomicLong()

    /** Takes one request from the budget of [key] under [limit]. */
    fun tryAcquire(
        key: String,
        limit: TokenBucket.Limit,
    ): TokenBucket.Decision {
        val now = nanoClock()
        var decision: TokenBucket.Decision? = null
        buckets.compute(key) { _, entry ->
            val current = entry?.takeIf { it.limit == limit }?.bucket ?: TokenBucket.full(limit, now)
            val (next, outcome) = current.take(limit, now)
            decision = outcome
            Entry(next, limit)
        }
        if (decisions.incrementAndGet() % SWEEP_EVERY == 0L) sweep(now)
        return checkNotNull(decision)
    }

    /** Number of clients currently tracked. */
    fun size(): Int = buckets.size

    /** Forgets every bucket that is full again at [nowNanos]. */
    fun sweep(nowNanos: Long = nanoClock()) {
        buckets.entries.removeIf { (_, entry) -> entry.bucket.isFull(entry.limit, nowNanos) }
    }

    private data class Entry(
        val bucket: TokenBucket,
        val limit: TokenBucket.Limit,
    )

    private companion object {
        const val SWEEP_EVERY = 4096L
    }
}

package com.ecommerce.notification.domain

import java.time.Duration

private const val DEFAULT_MAX_ATTEMPTS = 5
private const val DEFAULT_MULTIPLIER = 2L
private val DEFAULT_INITIAL_DELAY: Duration = Duration.ofSeconds(30)
private val DEFAULT_MAX_DELAY: Duration = Duration.ofMinutes(10)

/**
 * Delivery retry schedule (FR-019, data-model §1 "Notification retry"): at most [maxAttempts] attempts; after a
 * failed attempt `n` the next one waits [initialDelay] × [multiplier]^(n-1), capped at [maxDelay]. The defaults
 * are the decided values (5 attempts, exponential from 30 s, capped at 10 min); tests shorten them.
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val multiplier: Long = DEFAULT_MULTIPLIER,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelay > Duration.ZERO) { "initialDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay" }
        require(multiplier >= 1) { "multiplier must be at least 1" }
    }

    /** The wait after failed attempt number [attempt] (1-based) before the next one. */
    fun delayAfter(attempt: Int): Duration {
        require(attempt >= 1) { "attempts are numbered from 1" }
        var delay = initialDelay
        var step = 1
        while (step < attempt && delay < maxDelay) {
            delay = delay.multipliedBy(multiplier)
            step++
        }
        return minOf(delay, maxDelay)
    }

    /** True when [attempts] attempts used the whole budget. */
    fun exhausted(attempts: Int): Boolean = attempts >= maxAttempts

    /** The waits between consecutive attempts: `maxAttempts - 1` values. */
    fun schedule(): List<Duration> = (1 until maxAttempts).map(::delayAfter)
}

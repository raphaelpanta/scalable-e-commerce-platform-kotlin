package com.ecommerce.cart.infrastructure.jobs

import com.ecommerce.cart.application.PurgeIdleCarts
import com.ecommerce.platform.messaging.PeriodicJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Deletes the anonymous carts idle for longer than the configured timeout (30 days, data-model section 5) once at
 * start-up and then every [interval] (hourly by default), on the non-blocking [PeriodicJob] loop of
 * `libs/platform-messaging`, started and stopped with the application context.
 */
class CartPurgeJob(
    private val purgeIdleCarts: PurgeIdleCarts,
    interval: Duration,
) : SmartLifecycle {
    private val job =
        PeriodicJob("cart-purge", interval) {
            purgeNow()
            false
        }

    /** Runs one purge now; returns how many carts were deleted. */
    suspend fun purgeNow(): Int {
        val purged = purgeIdleCarts()
        if (purged > 0) log.info("Purged {} idle anonymous carts", purged)
        return purged
    }

    override fun start() = job.start()

    override fun stop() = job.stop()

    override fun stop(callback: Runnable) = job.stop(callback)

    override fun isRunning(): Boolean = job.isRunning

    private companion object {
        val log: Logger = LoggerFactory.getLogger(CartPurgeJob::class.java)
    }
}

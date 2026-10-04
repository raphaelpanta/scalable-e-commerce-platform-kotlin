package com.ecommerce.payment.infrastructure.jobs

import com.ecommerce.payment.application.PurgeCancelledOrders
import com.ecommerce.platform.messaging.PeriodicJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Forgets the cancelled orders (and their contact snapshot) remembered longer than the late-charge window
 * (`payment.cancelled-orders-retention`, data-model section 5, FR-007) once at start-up and then every [interval]
 * (`payment.cancelled-orders-purge-interval`), on the non-blocking [PeriodicJob] loop of `libs/platform-messaging`,
 * started and stopped with the application context. Only counts are logged. Several instances are safe: the purge is
 * one idempotent `DELETE`.
 */
class CancelledOrderPurgeJob(
    private val purgeCancelledOrders: PurgeCancelledOrders,
    interval: Duration,
) : SmartLifecycle {
    private val job =
        PeriodicJob("cancelled-order-purge", interval) {
            purgeNow()
            false
        }

    /** Runs one purge now; returns how many cancelled orders were forgotten. */
    suspend fun purgeNow(): Long {
        val purged = purgeCancelledOrders()
        if (purged > 0) log.info("Purged {} cancelled orders past the late-charge window", purged)
        return purged
    }

    override fun start() = job.start()

    override fun stop() = job.stop()

    override fun stop(callback: Runnable) = job.stop(callback)

    override fun isRunning(): Boolean = job.isRunning

    private companion object {
        val log: Logger = LoggerFactory.getLogger(CancelledOrderPurgeJob::class.java)
    }
}

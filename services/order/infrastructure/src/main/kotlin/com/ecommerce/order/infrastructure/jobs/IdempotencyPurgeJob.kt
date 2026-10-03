package com.ecommerce.order.infrastructure.jobs

import com.ecommerce.order.application.PurgeExpiredIdempotencyRecords
import com.ecommerce.platform.messaging.PeriodicJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Deletes the checkout idempotency records whose 24-hour replay window has ended (data-model section 5,
 * Constitution III) once at start-up and then every [interval] (`order.idempotency-purge.interval`, hourly by
 * default), on the non-blocking [PeriodicJob] loop of `libs/platform-messaging`, started and stopped with the
 * application context. Several instances are safe: the purge is one idempotent `DELETE`.
 */
class IdempotencyPurgeJob(
    private val purgeExpiredRecords: PurgeExpiredIdempotencyRecords,
    interval: Duration,
) : SmartLifecycle {
    private val job =
        PeriodicJob("idempotency-purge", interval) {
            purgeNow()
            false
        }

    /** Runs one purge now; returns how many records were deleted. */
    suspend fun purgeNow(): Long {
        val purged = purgeExpiredRecords()
        if (purged > 0) log.info("Purged {} expired idempotency records", purged)
        return purged
    }

    override fun start() = job.start()

    override fun stop() = job.stop()

    override fun stop(callback: Runnable) = job.stop(callback)

    override fun isRunning(): Boolean = job.isRunning

    private companion object {
        val log: Logger = LoggerFactory.getLogger(IdempotencyPurgeJob::class.java)
    }
}

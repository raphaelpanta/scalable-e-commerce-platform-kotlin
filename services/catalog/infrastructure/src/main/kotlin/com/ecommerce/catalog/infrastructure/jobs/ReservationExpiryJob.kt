package com.ecommerce.catalog.infrastructure.jobs

import com.ecommerce.catalog.application.ExpireReservations
import com.ecommerce.platform.messaging.PeriodicJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Releases reservations still `reserved` after their `expiresAt` (45 minutes by default, service conventions 8), at
 * start-up and then every [interval], on the non-blocking [PeriodicJob] loop of `libs/platform-messaging`. A full
 * batch runs again at once.
 */
class ReservationExpiryJob(
    private val expireReservations: ExpireReservations,
    batchSize: Int,
    interval: Duration,
) : SmartLifecycle {
    private val job = PeriodicJob("reservation-expiry", interval) { expireNow() >= batchSize }

    /** Runs one batch now; returns how many reservations were released. */
    suspend fun expireNow(): Int {
        val released = expireReservations()
        if (released > 0) log.info("Released {} expired stock reservations", released)
        return released
    }

    override fun start() = job.start()

    override fun stop() = job.stop()

    override fun stop(callback: Runnable) = job.stop(callback)

    override fun isRunning(): Boolean = job.isRunning

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ReservationExpiryJob::class.java)
    }
}

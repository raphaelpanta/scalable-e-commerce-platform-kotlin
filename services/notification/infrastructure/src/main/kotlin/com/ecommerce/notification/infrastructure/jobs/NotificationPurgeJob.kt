package com.ecommerce.notification.infrastructure.jobs

import com.ecommerce.notification.application.PurgeExpiredNotifications
import com.ecommerce.platform.messaging.PeriodicJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Deletes the terminal notifications (and their `delivery_attempts`) whose retention ended (90 days by default,
 * data-model section 5, FR-007) once at start-up and then every [interval] (hourly by default), on the non-blocking
 * [PeriodicJob] loop of `libs/platform-messaging`, started and stopped with the application context. Only counts are
 * logged, never an address or a message body.
 */
class NotificationPurgeJob(
    private val purgeExpiredNotifications: PurgeExpiredNotifications,
    interval: Duration,
) : SmartLifecycle {
    private val job =
        PeriodicJob("notification-purge", interval) {
            purgeNow()
            false
        }

    /** Runs one purge now; returns how many notifications were deleted. */
    suspend fun purgeNow(): Int {
        val purged = purgeExpiredNotifications()
        if (purged > 0) log.info("Purged {} notifications past retention", purged)
        return purged
    }

    override fun start() = job.start()

    override fun stop() = job.stop()

    override fun stop(callback: Runnable) = job.stop(callback)

    override fun isRunning(): Boolean = job.isRunning

    private companion object {
        val log: Logger = LoggerFactory.getLogger(NotificationPurgeJob::class.java)
    }
}

package com.ecommerce.identity.infrastructure.jobs

import com.ecommerce.identity.application.PurgeReport
import com.ecommerce.identity.application.PurgeRetainedData
import com.ecommerce.platform.messaging.PeriodicJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Deletes the identity records whose retention ended (data-model section 5, FR-007: tokens 7 days after expiry,
 * sessions 30 days after expiry or revocation, idle sign-in throttles, expired phone verifications) once at start-up
 * and then every [interval] (hourly by default), on the non-blocking [PeriodicJob] loop of `libs/platform-messaging`,
 * started and stopped with the application context. Only counts are logged, never a key or an address.
 */
class IdentityPurgeJob(
    private val purgeRetainedData: PurgeRetainedData,
    interval: Duration,
) : SmartLifecycle {
    private val job =
        PeriodicJob("identity-purge", interval) {
            purgeNow()
            false
        }

    /** Runs one purge now; returns what it deleted. */
    suspend fun purgeNow(): PurgeReport {
        val report = purgeRetainedData()
        if (report.total > 0) {
            log.info(
                "Purged {} tokens, {} sessions, {} sign-in throttles and {} phone verifications past retention",
                report.tokens,
                report.sessions,
                report.throttles,
                report.phoneVerifications,
            )
        }
        return report
    }

    override fun start() = job.start()

    override fun stop() = job.stop()

    override fun stop(callback: Runnable) = job.stop(callback)

    override fun isRunning(): Boolean = job.isRunning

    private companion object {
        val log: Logger = LoggerFactory.getLogger(IdentityPurgeJob::class.java)
    }
}

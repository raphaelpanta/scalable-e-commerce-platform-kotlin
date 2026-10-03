package com.ecommerce.platform.messaging.consumer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.Duration

/**
 * Deletes `processed_event` rows older than [retention] (7 days by default, longer than any broker redelivery
 * window). Runs hourly from the messaging auto-configuration (`platform.messaging.processed-events.*`).
 */
class ProcessedEventPurge(
    private val database: DatabaseClient,
    private val retention: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Deletes the expired rows and returns how many were removed. The statement is never cancelled half-way (see
     * `OutboxRelay.relayBatch`): a cancellation takes effect once it completed.
     */
    suspend fun purge(): Long =
        withContext(NonCancellable) {
            database
                .sql("DELETE FROM processed_event WHERE processed_at < :cutoff")
                .bind("cutoff", clock.instant().minus(retention))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
}

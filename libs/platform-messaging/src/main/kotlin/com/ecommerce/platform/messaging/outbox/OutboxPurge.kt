package com.ecommerce.platform.messaging.outbox

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.Duration

/** Deletes outbox rows published longer than [retention] ago (data-model §5: 7 days after publication). */
class OutboxPurge(
    private val database: DatabaseClient,
    private val retention: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Deletes the expired rows and returns how many were removed; unpublished rows are never deleted. */
    suspend fun purge(): Long =
        database
            .sql("DELETE FROM outbox WHERE published_at < :cutoff")
            .bind("cutoff", clock.instant().minus(retention))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
}

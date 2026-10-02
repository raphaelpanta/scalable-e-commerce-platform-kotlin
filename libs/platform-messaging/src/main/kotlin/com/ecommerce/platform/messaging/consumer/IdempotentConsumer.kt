package com.ecommerce.platform.messaging.consumer

import com.ecommerce.platform.messaging.envelope.EventEnvelope
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock

/** The outcome of handing an envelope to [IdempotentConsumer.handle]. */
sealed interface Handled<out T> {
    /** First delivery for this consumer: the block ran and its effects committed with the processed marker. */
    data class Processed<out T>(
        val value: T,
    ) : Handled<T>

    /** The `eventId` was already processed by this consumer: the block did not run. */
    data object Duplicate : Handled<Nothing>
}

/**
 * Exactly-once effects on top of at-least-once delivery (FR-022). [handle] records `(eventId, consumer)` in
 * `processed_event` and runs the block in the same reactive transaction, so the marker commits with the
 * block's database effects or not at all: a block that fails leaves no marker and the redelivery runs it again,
 * a repeated `eventId` is reported as [Handled.Duplicate] without running the block. Concurrent deliveries of one
 * `eventId` serialise on the primary key; the loser sees a duplicate once the winner commits. The block's writes
 * must use the same R2DBC connection factory (the service's `DatabaseClient`, repositories or `@Transactional`
 * code, which join this transaction).
 */
class IdempotentConsumer(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Runs [block] once per `eventId` of [envelope] for [consumer] (a stable handler name, e.g. the context). */
    suspend fun <E : EventEnvelope, T> handle(
        envelope: E,
        consumer: String,
        block: suspend (E) -> T,
    ): Handled<T> =
        transactions.executeAndAwait {
            if (markProcessed(envelope, consumer)) Handled.Processed(block(envelope)) else Handled.Duplicate
        }

    private suspend fun markProcessed(
        envelope: EventEnvelope,
        consumer: String,
    ): Boolean =
        database
            .sql(
                "INSERT INTO processed_event (event_id, consumer, processed_at) VALUES (:eventId, :consumer, :now) " +
                    "ON CONFLICT DO NOTHING",
            ).bind("eventId", envelope.eventId)
            .bind("consumer", consumer)
            .bind("now", clock.instant())
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L
}

package com.ecommerce.platform.messaging.outbox

import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.module.kotlin.jacksonTypeRef
import java.time.Duration
import java.util.UUID
import kotlin.time.toKotlinDuration
import org.apache.kafka.common.KafkaException as ClientKafkaException
import org.springframework.kafka.KafkaException as SpringKafkaException

private const val MAX_ERROR_LENGTH = 2000

/** One outbox row on its way to Kafka. */
private data class PendingRecord(
    val id: UUID,
    val topic: String,
    val key: String,
    val value: String,
    val headers: Map<String, String>,
)

/**
 * Relays outbox rows to Kafka, oldest first. Each batch runs in one database transaction that locks its rows
 * (`FOR UPDATE SKIP LOCKED`), so several instances of a service never send the same row concurrently. A row is
 * sent with key = aggregate id, value = the envelope JSON and the headers `eventId`, `type` and `correlationId`;
 * `published_at` is set once Kafka acknowledged it, otherwise `attempts` and `last_error` record the failure and
 * the row is retried by a later batch. After a failure the remaining rows of the same key wait for the next
 * batch, which keeps the per-aggregate order. Delivery is at-least-once: a row whose acknowledgment was lost is
 * sent again with the same `eventId`.
 */
class OutboxRelay(
    private val database: DatabaseClient,
    private val transactions: TransactionalOperator,
    private val kafka: KafkaOperations<String, String>,
    private val settings: Settings,
    meters: MeterRegistry,
    /** Where `KafkaProducer.send` runs: it may block while it fetches metadata, so never on an event loop. */
    private val sendDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Batch size and the longest wait for one Kafka acknowledgment. */
    data class Settings(
        val batchSize: Int,
        val sendTimeout: Duration,
    )

    private val published: Counter =
        Counter
            .builder("outbox.published")
            .description("Outbox rows acknowledged by Kafka")
            .register(meters)
    private val failed: Counter =
        Counter
            .builder("outbox.failed")
            .description("Outbox send attempts that failed and will be retried")
            .register(meters)

    /**
     * Relays one batch and returns the number of rows Kafka acknowledged; a full batch means more rows may be
     * waiting, a batch with failures waits for the poll interval before the next attempt.
     *
     * Cancellation (the relay job stopping with the application) is honoured between rows, never inside a
     * statement: the transaction runs [NonCancellable], stops sending once the caller is cancelled, commits the
     * rows Kafka already acknowledged and only then rethrows the cancellation. Cancelling an R2DBC statement in
     * flight drops the bind parameters r2dbc-postgresql has already encoded into Netty buffers without releasing
     * them (`LEAK: ByteBuf.release() was not called`) and lets the pool close the connection under the statement.
     * A stop therefore waits for at most the statement in flight and one Kafka send ([Settings.sendTimeout]).
     */
    suspend fun relayBatch(): Int {
        val caller = currentCoroutineContext().job
        val acknowledged = withContext(NonCancellable) { relayBatchUntil { !caller.isActive } }
        caller.ensureActive()
        return acknowledged
    }

    private suspend fun relayBatchUntil(cancelled: () -> Boolean): Int =
        transactions.executeAndAwait {
            val blockedKeys = HashSet<String>()
            var acknowledged = 0
            for (row in lockPending().asSequence().takeWhile { !cancelled() }) {
                if (row.key in blockedKeys) continue
                val failure = send(row)
                if (failure == null) {
                    markPublished(row.id)
                    published.increment()
                    acknowledged++
                } else {
                    blockedKeys += row.key
                    markFailed(row.id, failure)
                    failed.increment()
                }
            }
            acknowledged
        }

    private suspend fun lockPending(): List<PendingRecord> =
        database
            .sql(SELECT_PENDING)
            .bind("limit", settings.batchSize)
            .map { row, _ ->
                PendingRecord(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    topic = checkNotNull(row.get("topic", String::class.java)),
                    key = checkNotNull(row.get("event_key", String::class.java)),
                    value = checkNotNull(row.get("payload", String::class.java)),
                    headers =
                        EnvelopeJson.mapper.readValue(
                            checkNotNull(row.get("headers", String::class.java)),
                            HEADERS,
                        ),
                )
            }.flow()
            .toList()

    /** Sends [row] and returns null on acknowledgment, otherwise a description of the failure. */
    private suspend fun send(row: PendingRecord): String? {
        val record =
            ProducerRecord<String, String>(row.topic, null, row.key, compact(row.value), recordHeaders(row.headers))
        return try {
            val acknowledged =
                withTimeoutOrNull(settings.sendTimeout.toKotlinDuration()) {
                    withContext(sendDispatcher) { kafka.send(record) }.await()
                }
            if (acknowledged == null) "no acknowledgment within ${settings.sendTimeout}" else null
        } catch (failure: SpringKafkaException) {
            describe(row, failure)
        } catch (failure: ClientKafkaException) {
            describe(row, failure)
        }
    }

    private fun describe(
        row: PendingRecord,
        failure: Exception,
    ): String {
        log.warn("Outbox row {} could not be sent to {}", row.id, row.topic, failure)
        return (failure.message ?: failure.javaClass.name).take(MAX_ERROR_LENGTH)
    }

    private suspend fun markPublished(id: UUID) {
        database
            .sql(
                "UPDATE outbox SET published_at = clock_timestamp(), attempts = attempts + 1, last_error = NULL " +
                    "WHERE id = :id",
            ).bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun markFailed(
        id: UUID,
        error: String,
    ) {
        database
            .sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
            .bind("error", error)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OutboxRelay::class.java)
        val HEADERS = jacksonTypeRef<Map<String, String>>()
        const val SELECT_PENDING =
            "SELECT id, topic, event_key, payload::text AS payload, headers::text AS headers FROM outbox " +
                "WHERE published_at IS NULL ORDER BY created_at, position LIMIT :limit FOR UPDATE SKIP LOCKED"

        /** jsonb prints `{"a": 1}`; the record value is the compact form `{"a":1}`. */
        fun compact(json: String): String = EnvelopeJson.mapper.readTree(json).toString()

        fun recordHeaders(headers: Map<String, String>): List<RecordHeader> =
            headers.map { (name, value) -> RecordHeader(name, value.toByteArray(Charsets.UTF_8)) }
    }
}

package com.ecommerce.platform.messaging.it

import com.ecommerce.platform.messaging.PeriodicJob
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.messaging.outbox.OutboxRelay
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.OutboxTestSupport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.ResourceLeakDetector
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.SendResult
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The relay releases every Netty buffer of its R2DBC connection, also when it is cancelled (the application
 * stopping) in the middle of a batch. Netty's leak detector runs at `PARANOID` (every buffer tracked); after a
 * garbage collection the next allocation logs any leaked buffer (`LEAK: ...`), which the tests read from the
 * captured output. Cancelling an R2DBC statement in flight used to drop the bind parameters r2dbc-postgresql had
 * already encoded (created by `OutboxRelay.markPublished`, never released).
 */
@MessagingIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class RelayResourceLeakIntegrationTest(
    @Autowired private val outbox: OutboxPublisher,
    @Autowired @Qualifier("outboxRelayJob") private val relayJob: PeriodicJob,
    @Autowired private val producerFactory: ProducerFactory<String, String>,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val database: DatabaseClient,
    @Autowired transactionManager: ReactiveTransactionManager,
) {
    private val transactions = TransactionalOperator.create(transactionManager)
    private val rows = OutboxTestSupport(database)
    private var previousLevel: ResourceLeakDetector.Level = ResourceLeakDetector.getLevel()

    @BeforeEach
    fun trackEveryBuffer() {
        previousLevel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    @AfterEach
    fun restoreLeakDetection() {
        ResourceLeakDetector.setLevel(previousLevel)
        relayJob.start()
    }

    @Test
    fun `relay batches cancelled in the middle of their statements leak no Netty buffer`(output: CapturedOutput) {
        // The application's relay publishes what other tests left behind, then stays out of the way.
        rows.awaitDrained()
        stopAndAwait(relayJob)
        publish(EVENTS)

        val random = Random(SEED)
        Executors.newSingleThreadExecutor().use { acknowledger ->
            val kafka = CancellingKafka(producerFactory, acknowledger)
            val relay = relayOn(kafka)
            // Several threads, as in production: the batch runs on them while the cancellation comes from the
            // thread that acknowledges a send, just as the relay goes on to mark the row published.
            relayThreads().use { threads ->
                runBlocking(threads) {
                    repeat(CANCELLED_BATCHES) {
                        val batch = launch(start = CoroutineStart.LAZY) { relay.relayBatch() }
                        kafka.cancelOnAcknowledgment(
                            batch,
                            send = random.nextInt(1, MAX_SENDS_BEFORE_CANCEL),
                            afterNanos = random.nextLong(MAX_CANCEL_LAG_NANOS),
                        )
                        batch.start()
                        batch.join()
                    }
                    kafka.cancelOnAcknowledgment(null, send = 0, afterNanos = 0)
                    // Drains what the cancelled batches left, so that no test event reaches Kafka later.
                    do {
                        val relayed = relay.relayBatch()
                    } while (relayed > 0)
                }
            }
        }

        leakReports(output).shouldBeEmpty()
    }

    @Test
    fun `stopping the relay job while it polls waits for the batch and leaks no Netty buffer`(output: CapturedOutput) {
        val random = Random(SEED)
        repeat(LIFECYCLE_CYCLES) {
            publish(EVENTS_PER_CYCLE)
            relayJob.start()
            Thread.sleep(random.nextLong(MAX_CANCEL_DELAY_MS))
            stopAndAwait(relayJob)
        }
        relayJob.start()
        rows.awaitDrained()

        leakReports(output).shouldBeEmpty()
    }

    private fun relayThreads(): ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(RELAY_THREADS).asCoroutineDispatcher()

    private fun publish(events: Int) {
        val envelopes = List(events) { EnvelopeFixtures.orderPlaced(orderId = UUID.randomUUID()) }
        runBlocking { transactions.executeAndAwait { outbox.publishAll(envelopes) } }
    }

    private fun relayOn(kafka: KafkaTemplate<String, String>): OutboxRelay =
        OutboxRelay(
            database = database,
            transactions = transactions,
            kafka = kafka,
            settings = OutboxRelay.Settings(batchSize = BATCH_SIZE, sendTimeout = Duration.ofSeconds(1)),
            meters = meters,
        )

    /** Collects unreachable buffers; the detector reports them on its next tracked allocation (a query). */
    private fun leakReports(output: CapturedOutput): List<String> {
        repeat(GC_ROUNDS) {
            System.gc()
            Thread.sleep(GC_PAUSE_MS)
            rows.pending()
        }
        return output.all
            .lines()
            .filter { LEAK in it }
            .map { it.take(MAX_REPORT_LENGTH) }
    }

    private fun stopAndAwait(job: PeriodicJob) {
        val stopped = CountDownLatch(1)
        job.stop { stopped.countDown() }
        stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
        job.isRunning shouldBe false
    }

    private companion object {
        /** The start of Netty's leak report (`io.netty.util.ResourceLeakDetector`, logged at ERROR). */
        const val LEAK = "LEAK: "
        const val MAX_REPORT_LENGTH = 4000
        const val EVENTS = 3000
        const val BATCH_SIZE = 100
        const val CANCELLED_BATCHES = 500
        const val MAX_SENDS_BEFORE_CANCEL = 20
        const val MAX_CANCEL_LAG_NANOS = 300_000L
        const val MAX_CANCEL_DELAY_MS = 40L
        const val LIFECYCLE_CYCLES = 10
        const val EVENTS_PER_CYCLE = 20
        const val SEED = 42
        const val RELAY_THREADS = 4
        const val GC_ROUNDS = 3
        const val GC_PAUSE_MS = 200L
        const val STOP_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Kafka that acknowledges every record at once, except the armed send: that one is acknowledged from
 * [acknowledger], which then cancels the armed batch after a short spin, while the relay resumes to mark the row
 * published.
 */
private class CancellingKafka(
    producerFactory: ProducerFactory<String, String>,
    private val acknowledger: Executor,
) : KafkaTemplate<String, String>(producerFactory) {
    private val sends = AtomicInteger()

    @Volatile
    private var victim: Job? = null

    @Volatile
    private var cancelAtSend = 0

    @Volatile
    private var lagNanos = 0L

    fun cancelOnAcknowledgment(
        batch: Job?,
        send: Int,
        afterNanos: Long,
    ) {
        sends.set(0)
        victim = batch
        cancelAtSend = send
        lagNanos = afterNanos
    }

    override fun send(record: ProducerRecord<String, String>): CompletableFuture<SendResult<String, String>> {
        val acknowledgment = SendResult(record, RecordMetadata(TopicPartition(record.topic(), 0), 0, 0, 0, 0, 0))
        val batch = victim
        if (batch == null || sends.incrementAndGet() != cancelAtSend) {
            return CompletableFuture.completedFuture(acknowledgment)
        }
        val acknowledged = CompletableFuture<SendResult<String, String>>()
        acknowledger.execute {
            acknowledged.complete(acknowledgment)
            val until = System.nanoTime() + lagNanos
            while (System.nanoTime() < until) Thread.onSpinWait()
            batch.cancel()
        }
        return acknowledged
    }
}

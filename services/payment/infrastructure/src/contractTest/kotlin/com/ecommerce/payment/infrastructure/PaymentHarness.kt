package com.ecommerce.payment.infrastructure

import com.ecommerce.payment.application.PaymentLedger
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.infrastructure.messaging.PaymentEventListeners
import com.ecommerce.platform.messaging.envelope.Topic
import kotlinx.coroutines.reactor.mono
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.support.Acknowledgment
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.util.UUID

private val TIMEOUT: Duration = Duration.ofSeconds(10)

/** Test wiring of the contract layer: the [PaymentHarness] over the service's own beans. */
@TestConfiguration(proxyBeanMethods = false)
class ContractTestConfig {
    @Bean
    fun paymentHarness(
        listener: PaymentEventListeners,
        ledger: PaymentLedger,
        database: DatabaseClient,
    ): PaymentHarness = PaymentHarness(listener, ledger, database)
}

/**
 * Arranges provider states through the service's own repositories, feeds pact messages to the real Kafka listener
 * and reads back what they produced (blocking on purpose: test code on the JUnit thread).
 */
class PaymentHarness(
    private val listener: PaymentEventListeners,
    private val ledger: PaymentLedger,
    private val database: DatabaseClient,
) {
    /** Empties the service's tables: every provider state describes all the data its interaction needs. */
    fun reset() {
        database
            .sql("TRUNCATE refunds, payment_attempts, processed_event, outbox")
            .fetch()
            .rowsUpdated()
            .block(TIMEOUT)
    }

    /** Stores [attempt] as it is. */
    fun store(attempt: PaymentAttempt) {
        check(mono { ledger.attempts.insert(attempt) }.block(TIMEOUT) == true) { "attempt ${attempt.id} not stored" }
    }

    /** Stores [refund] as it is. */
    fun store(refund: RefundRecord) {
        check(mono { ledger.refunds.insert(refund) }.block(TIMEOUT) == true) { "refund ${refund.id} not stored" }
    }

    /** Hands [json] (a pact message) to the listener as a record of `order.order.v1` keyed by [key]. */
    fun deliverOrderEvent(
        key: String,
        json: String,
    ) {
        listener.onOrder(ConsumerRecord(Topic.ORDER, 0, 0L, key, json), Acknowledgment { })
    }

    /** `outcome/idempotencyKey` of every attempt of [orderId]. */
    fun attemptsOf(orderId: String): List<String> =
        strings(
            "SELECT outcome || '/' || idempotency_key AS v FROM payment_attempts WHERE order_id = :id",
            UUID.fromString(orderId),
        )

    /** `attemptId/amountMinor/announced` of every refund of [orderId]. */
    fun refundsOf(orderId: String): List<String> =
        strings(
            "SELECT attempt_id || '/' || amount_minor || '/' || (announced_at IS NOT NULL) AS v FROM refunds " +
                "WHERE order_id = :id",
            UUID.fromString(orderId),
        )

    /** `type/correlationId` of every event stored in the outbox, in publication order. */
    fun outbox(): List<String> =
        strings(
            "SELECT event_type || '/' || (payload ->> 'correlationId') AS v FROM outbox ORDER BY position",
            null,
        )

    /** Times [eventId] was recorded as processed by the `payment` consumer. */
    fun processed(eventId: UUID): List<String> =
        strings("SELECT consumer AS v FROM processed_event WHERE event_id = :id", eventId)

    private fun strings(
        sql: String,
        id: Any?,
    ): List<String> =
        database
            .sql(sql)
            .let { spec -> if (id == null) spec else spec.bind("id", id) }
            .map { row -> row.get("v", String::class.java).orEmpty() }
            .all()
            .collectList()
            .block(TIMEOUT)
            .orEmpty()
}

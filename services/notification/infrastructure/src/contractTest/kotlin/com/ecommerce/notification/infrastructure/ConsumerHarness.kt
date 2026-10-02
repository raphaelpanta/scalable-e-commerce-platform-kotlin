package com.ecommerce.notification.infrastructure

import com.ecommerce.notification.infrastructure.messaging.NotificationEventListener
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.support.Acknowledgment
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistrar
import java.time.Duration
import java.util.UUID

private val TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * Test wiring of the message pacts: an identity stand-in (WireMock) answering the contact lookup of any account
 * with an email-only contact, and the [ConsumerHarness] that feeds pact messages to the real Kafka listener.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContractTestConfig {
    @Bean(destroyMethod = "stop")
    fun identityStub(): WireMockServer =
        WireMockServer(options().dynamicPort()).apply {
            start()
            stubFor(
                get(urlPathMatching("/internal/accounts/[0-9a-f-]+/contact")).willReturn(
                    aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","email":"ada@example.test",""" +
                                """"phoneVerified":false,"channels":["email"],"anonymised":false}""",
                        ),
                ),
            )
        }

    @Bean
    fun identityStubProperties(identityStub: WireMockServer): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry -> registry.add("notification.identity-url") { identityStub.baseUrl() } }

    @Bean
    fun consumerHarness(
        listener: NotificationEventListener,
        database: DatabaseClient,
    ): ConsumerHarness = ConsumerHarness(listener, database)
}

/** Delivers pact messages to the service's real listener and reads back what they produced. */
class ConsumerHarness(
    private val listener: NotificationEventListener,
    private val database: DatabaseClient,
) {
    /** Empties the service's tables before each test. */
    fun reset() {
        database
            .sql("TRUNCATE delivery_attempts, notifications, recipients, processed_event, outbox")
            .fetch()
            .rowsUpdated()
            .block(TIMEOUT)
    }

    /** Hands [json] (a pact message) to the listener as a record of [topic] keyed by [key]. */
    fun deliver(
        topic: String,
        key: String,
        json: String,
    ) {
        listener.onEvent(ConsumerRecord(topic, 0, 0L, key, json), Acknowledgment { })
    }

    /** `kind/channel/status` of every notification produced by [eventId]. */
    fun notificationsOf(eventId: UUID): List<String> =
        database
            .sql("SELECT kind, channel, status FROM notifications WHERE source_event_id = :eventId ORDER BY channel")
            .bind("eventId", eventId)
            .map { row ->
                "${row.get("kind", String::class.java)}/${row.get("channel", String::class.java)}/" +
                    row.get("status", String::class.java)
            }.all()
            .collectList()
            .block(TIMEOUT)
            .orEmpty()

    /** The read-model row of [accountId] as `verified/anonymised`, or null. */
    fun recipient(accountId: UUID): String? =
        database
            .sql("SELECT email_verified, anonymised FROM recipients WHERE account_id = :accountId")
            .bind("accountId", accountId)
            .map { row ->
                "${row.get("email_verified", Boolean::class.javaObjectType)}/" +
                    row.get("anonymised", Boolean::class.javaObjectType)
            }.one()
            .block(TIMEOUT)

    /** Times [eventId] was recorded as processed by the `notification` consumer. */
    fun processed(eventId: UUID): Long =
        database
            .sql("SELECT count(*) AS n FROM processed_event WHERE event_id = :eventId AND consumer = 'notification'")
            .bind("eventId", eventId)
            .map { row -> row.get("n", Long::class.javaObjectType) ?: 0L }
            .one()
            .block(TIMEOUT) ?: 0L

    /** Message bodies of [eventId]'s notifications (to check what the templates received). */
    fun bodiesOf(eventId: UUID): List<String> =
        database
            .sql("SELECT body FROM notifications WHERE source_event_id = :eventId")
            .bind("eventId", eventId)
            .map { row -> row.get("body", String::class.java).orEmpty() }
            .all()
            .collectList()
            .block(TIMEOUT)
            .orEmpty()

    /** Inserts a queued email notification for [accountId] (state for the AccountDeleted pact). */
    fun queueNotificationFor(accountId: UUID) {
        database
            .sql(
                "INSERT INTO notifications (id, source_event_id, kind, channel, account_id, recipient_address, " +
                    "subject, body, correlation_id, status, attempts, next_attempt_at, created_at) VALUES (:id, " +
                    ":eventId, 'order_shipped', 'email', :accountId, 'ada@example.test', 'Order shipped', 'body', " +
                    "'3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13', 'queued', 0, now(), now())",
            ).bind("id", UUID.randomUUID())
            .bind("eventId", UUID.randomUUID())
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .block(TIMEOUT)
    }

    /** `status/address-present` of every notification of [accountId]. */
    fun statesOf(accountId: UUID): List<String> =
        database
            .sql("SELECT status, recipient_address FROM notifications WHERE account_id = :accountId")
            .bind("accountId", accountId)
            .map { row ->
                "${row.get("status", String::class.java)}/${row.get("recipient_address", String::class.java) != null}"
            }.all()
            .collectList()
            .block(TIMEOUT)
            .orEmpty()
}

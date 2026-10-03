package com.ecommerce.notification.infrastructure

import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.MailpitContainer
import com.ecommerce.platform.testing.PostgresTestConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val BUDGET: Duration = Duration.ofSeconds(30)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val SEND_SECONDS = 10L

/** One lookup by the consumer, one failed and one successful delivery round. */
private const val LOOKUPS_WITH_TWO_FAILURES = 3

/**
 * T158 (FR-018, FR-019): identity answers 503 and the event carries no recipient snapshot. The event is consumed (not
 * dead-lettered), its message is queued awaiting its recipient, and the delivery job sends it once identity answers
 * 200. Same context as [DeliveryIT].
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class, DeliveryTestConfig::class)
class DeferredRecipientIT(
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val mailpit: MailpitContainer,
    @Autowired private val identity: WireMockServer,
    @Autowired private val recorded: RecordedEvents,
    @Autowired private val database: DatabaseClient,
) {
    @Test
    fun `AccountRegistered with identity down is queued and its verification email sent once identity answers`() {
        val accountId = UUID.randomUUID()
        val email = "deferred-$accountId@example.test"
        // The lookup of the consumer and the first delivery round fail; the second round gets the contact.
        IdentityStub.unavailableThenEmailOnly(identity, accountId, email, failures = 2)
        val token = "verify-" + UUID.randomUUID()
        val event =
            EnvelopeFixtures.envelope(
                EventType.AccountRegistered,
                mapOf("accountId" to "$accountId", "verificationToken" to token),
                aggregateId = accountId,
            )
        publish(event)

        val message = awaitMessageTo(email)
        message.subject shouldBe "Verify your email address"
        mailpit.text(message.id) shouldContain "/verify?token=$token"
        awaitStored(event.eventId) shouldBe Stored("sent", attempts = 2, awaiting = false)
        attempts(event.eventId) shouldBe listOf(false, true)
        identity.verify(LOOKUPS_WITH_TWO_FAILURES, getRequestedFor(urlEqualTo("/internal/accounts/$accountId/contact")))
        recorded
            .awaitType(EventType.NotificationSent) { it.payload.path("accountId").asString() == "$accountId" }
            .payload
            .path("template")
            .asString() shouldBe "account-verification"
    }

    @Test
    fun `a PasswordResetRequested whose recipient lookup fails is queued and sent by the next delivery round`() {
        val accountId = UUID.randomUUID()
        val email = "deferred-reset-$accountId@example.test"
        IdentityStub.unavailableThenEmailOnly(identity, accountId, email, failures = 1)
        val token = "reset-" + UUID.randomUUID()
        val event =
            EnvelopeFixtures.envelope(
                EventType.PasswordResetRequested,
                mapOf("accountId" to "$accountId", "resetToken" to token),
                aggregateId = accountId,
            )
        publish(event)

        val message = awaitMessageTo(email)
        message.subject shouldBe "Reset your password"
        mailpit.text(message.id) shouldContain "/reset-password?token=$token"
        awaitStored(event.eventId) shouldBe Stored("sent", attempts = 1, awaiting = false)
        attempts(event.eventId) shouldBe listOf(true)
    }

    private data class Stored(
        val status: String,
        val attempts: Int,
        val awaiting: Boolean,
    )

    private fun publish(envelope: Envelope<*>) {
        kafka
            .send(EventType.topicOf(envelope.type), envelope.aggregateId.toString(), EnvelopeJson.write(envelope))
            .get(SEND_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitMessageTo(address: String): MailpitContainer.Message {
        await().atMost(BUDGET).until { mailpit.messages().any { address in it.to } }
        return mailpit.messages().first { address in it.to }
    }

    private fun awaitStored(eventId: UUID): Stored? {
        var stored: Stored? = null
        await().atMost(BUDGET).until {
            stored = stored(eventId)
            stored?.status == "sent"
        }
        return stored
    }

    private fun stored(eventId: UUID): Stored? =
        database
            .sql("SELECT status, attempts, awaiting_recipient FROM notifications WHERE source_event_id = :eventId")
            .bind("eventId", eventId)
            .map { row ->
                Stored(
                    row.get("status", String::class.java).orEmpty(),
                    row.get("attempts", Int::class.javaObjectType) ?: 0,
                    row.get("awaiting_recipient", Boolean::class.javaObjectType) ?: true,
                )
            }.one()
            .block(QUERY_TIMEOUT)

    /** Whether each attempt of the event's notification succeeded, in order. */
    private fun attempts(eventId: UUID): List<Boolean> =
        database
            .sql(
                "SELECT a.succeeded FROM delivery_attempts a JOIN notifications n ON n.id = a.notification_id " +
                    "WHERE n.source_event_id = :eventId ORDER BY a.attempt",
            ).bind("eventId", eventId)
            .map { row -> row.get("succeeded", Boolean::class.javaObjectType) ?: false }
            .all()
            .collectList()
            .block(QUERY_TIMEOUT)
            .orEmpty()
}

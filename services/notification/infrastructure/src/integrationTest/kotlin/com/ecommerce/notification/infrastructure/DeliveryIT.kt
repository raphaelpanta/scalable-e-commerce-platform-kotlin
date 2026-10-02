package com.ecommerce.notification.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.MailpitContainer
import com.ecommerce.platform.testing.PostgresTestConfig
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import com.github.tomakehurst.wiremock.WireMockServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val DELIVERY_BUDGET: Duration = Duration.ofSeconds(30)
private val QUIET_PERIOD: Duration = Duration.ofSeconds(2)
private val TIMEOUT: Duration = Duration.ofSeconds(10)
private const val SEND_SECONDS = 10L
private const val MAX_ATTEMPTS = 5
private const val PHONE_DIGITS = 9
private const val ORDER_TOTAL = 4000L
private const val UNIT_PRICE = 2000L
private const val ACCEPTED = 202
private const val BAD_REQUEST = 400
private const val CONFLICT = 409
private val MEMBERS = object : ParameterizedTypeReference<Map<String, Any?>>() {}

/**
 * T087/T088: the service against real PostgreSQL, Kafka and Mailpit containers, a WireMock identity and signed
 * tokens. Events are published to their topics; messages are read back from Mailpit, outcomes from the
 * `notification.notification.v1` topic and the operator/shopper API. The retry schedule is shortened in
 * `config/application.yml` (5 attempts from 200 ms, capped at 800 ms).
 */
@Suppress("TooManyFunctions", "LongParameterList") // one test per behaviour; one injected collaborator per container
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class, DeliveryTestConfig::class)
class DeliveryIT(
    @LocalServerPort port: Int,
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val mailpit: MailpitContainer,
    @Autowired private val identity: WireMockServer,
    @Autowired private val jwt: JwtFixture,
    @Autowired private val recorded: RecordedEvents,
    @Autowired private val database: DatabaseClient,
) {
    private val client: WebTestClient =
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(TIMEOUT)
            .build()

    @Test
    fun `an OrderPaid event sends one confirmation email with the order details and publishes NotificationSent`() {
        val shopper = Shopper.withEmailOnly(identity)
        val order = UUID.randomUUID()
        publish(orderPaid(shopper.accountId, order))

        val message = awaitMessageTo(shopper.email)
        message.subject shouldBe "Order ORD-20261002-0001 confirmed"
        val text = mailpit.text(message.id)
        val details = listOf("ORD-20261002-0001", "$order", "2 x Puzzle at BRL 20.00", "Total: BRL 40.00")
        (details + "Rua das Flores 12").forEach { text shouldContain it }

        val sent =
            recorded.awaitType(EventType.NotificationSent) {
                it.payload.path("accountId").asString() == "${shopper.accountId}"
            }
        sent.payload.path("template").asString() shouldBe "order-confirmation"
        sent.payload.path("channel").asString() shouldBe "email"
        sent.payload.path("attempts").asInt() shouldBe 1
        sent.correlationId shouldBe EnvelopeFixtures.CORRELATION_ID
    }

    @Test
    fun `the same eventId delivered twice sends one message`() {
        val shopper = Shopper.withEmailOnly(identity)
        val event = orderPaid(shopper.accountId, UUID.randomUUID())
        publish(event)
        publish(event)

        awaitMessageTo(shopper.email)
        await()
            .during(QUIET_PERIOD)
            .atMost(QUIET_PERIOD.plusSeconds(1))
            .until { messagesTo(shopper.email).size == 1 }
        count("SELECT count(*) AS n FROM notifications WHERE source_event_id = '${event.eventId}'") shouldBe 1L
    }

    @Test
    fun `a failing SMTP server is retried, then failed and visible to operators, and an operator retry delivers`() {
        val shopper = Shopper.withEmailOnly(identity)
        MailpitChaos.refuseEveryRecipient(mailpit)
        val failed =
            try {
                publish(orderPaid(shopper.accountId, UUID.randomUUID()))
                awaitFailed(shopper.accountId)
            } finally {
                MailpitChaos.acceptEveryRecipient(mailpit)
            }
        failed["attempts"] shouldBe MAX_ATTEMPTS
        failed["lastError"] shouldBe "SMTP server refused the message (451)."
        failed["accountId"] shouldBe "${shopper.accountId}"
        val id = failed["id"].toString()
        val attempts = count("SELECT count(*) AS n FROM delivery_attempts WHERE notification_id = '$id'")
        attempts shouldBe MAX_ATTEMPTS.toLong()
        val outcome =
            recorded.awaitType(EventType.NotificationFailed) { it.payload.path("notificationId").asString() == id }
        outcome.payload.path("lastErrorCategory").asString() shouldBe "CHANNEL_UNAVAILABLE"

        val requeued = post("/api/v1/notifications/$id/retry", operator()).expectStatus().isEqualTo(ACCEPTED).body()
        requeued["status"] shouldBe "queued"
        requeued["attempts"] shouldBe 0
        awaitMessageTo(shopper.email)
        await().atMost(TIMEOUT).until { own(shopper).single()["status"] == "sent" }
    }

    @Test
    fun `a shopper opted in to SMS gets an email and an SMS mirrored to Mailpit when the order ships`() {
        val phone = "+55119" + (1..PHONE_DIGITS).joinToString("") { Random.nextInt(0, 10).toString() }
        val shopper = Shopper.withSms(identity, phone)
        publish(orderShipped(shopper.accountId, UUID.randomUUID()))

        awaitMessageTo(shopper.email).subject shouldBe "Order ORD-20261002-0001 has shipped"
        val sms = awaitMessageTo("sms-${phone.removePrefix("+")}@sms.ecommerce.invalid")
        sms.subject shouldBe "SMS to $phone"
        mailpit.text(sms.id) shouldContain "Order ORD-20261002-0001 has shipped."
        await().atMost(TIMEOUT).until { own(shopper).count { it["status"] == "sent" } == 2 }
        own(shopper).map { it["channel"] }.toSet() shouldBe setOf("email", "sms")
    }

    @Test
    fun `an anonymised account receives nothing and the message is recorded as suppressed`() {
        val shopper = Shopper.anonymised(identity)
        val event = orderShipped(shopper.accountId, UUID.randomUUID())
        publish(event)

        await().atMost(DELIVERY_BUDGET).until { own(shopper).isNotEmpty() }
        own(shopper).single()["status"] shouldBe "suppressed"
        messagesTo("anon-4f9c2d71@anonymised.invalid") shouldHaveSize 0
    }

    @Test
    fun `shoppers read only their own notifications, newest first, without bodies or addresses`() {
        val shopper = Shopper.withEmailOnly(identity)
        val other = Shopper.withEmailOnly(identity)
        publish(orderPaid(shopper.accountId, UUID.randomUUID()))
        publish(orderShipped(shopper.accountId, UUID.randomUUID()))
        publish(orderPaid(other.accountId, UUID.randomUUID()))
        await().atMost(DELIVERY_BUDGET).until { own(shopper).size == 2 && own(other).size == 1 }

        val page = get("/api/v1/notifications?size=1", shopper.token(jwt)).expectStatus().isOk.body()
        page["totalItems"] shouldBe 2
        page["size"] shouldBe 1
        val item = (page["items"] as List<*>).single() as Map<*, *>
        item["type"] shouldBe "order_shipped"
        listOf("id", "channel", "type", "status", "attempts", "orderId", "createdAt").forEach {
            item.keys shouldContain
                it
        }
        listOf("accountId", "body", "recipient", "email").forEach { (it in item.keys) shouldBe false }
        val filtered = get("/api/v1/notifications?type=order_confirmation&channel=email", shopper.token(jwt)).body()
        filtered["totalItems"] shouldBe 1
    }

    @Test
    fun `the API enforces roles, validates parameters and refuses to retry a notification that is not failed`() {
        val shopper = Shopper.withEmailOnly(identity)
        publish(orderPaid(shopper.accountId, UUID.randomUUID()))
        await().atMost(DELIVERY_BUDGET).until { own(shopper).singleOrNull()?.get("status") == "sent" }
        val id = own(shopper).single()["id"]

        client
            .get()
            .uri("/api/v1/notifications")
            .exchange()
            .expectProblem(ProblemType.UNAUTHORIZED)
        get("/api/v1/notifications/failed", shopper.token(jwt)).expectProblem(ProblemType.FORBIDDEN)
        post("/api/v1/notifications/$id/retry", shopper.token(jwt)).expectProblem(ProblemType.FORBIDDEN)
        get("/api/v1/notifications?channel=fax", shopper.token(jwt)).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        get("/api/v1/notifications?size=101", shopper.token(jwt)).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        get("/api/v1/notifications/failed?failedFrom=yesterday", operator())
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        post("/api/v1/notifications/not-a-uuid/retry", operator()).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        post("/api/v1/notifications/${UUID.randomUUID()}/retry", operator()).expectProblem(ProblemType.NOT_FOUND)
        val conflict = post("/api/v1/notifications/$id/retry", operator()).expectStatus().isEqualTo(CONFLICT).body()
        conflict["type"] shouldBe "https://ecommerce.example/problems/notification-not-failed"
        conflict["detail"].toString() shouldContain "this one is sent"
    }

    private fun publish(envelope: Envelope<*>) {
        val topic = EventType.topicOf(envelope.type)
        kafka
            .send(topic, envelope.aggregateId.toString(), EnvelopeJson.write(envelope))
            .get(SEND_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitMessageTo(address: String): MailpitContainer.Message {
        await().atMost(DELIVERY_BUDGET).until { messagesTo(address).isNotEmpty() }
        return messagesTo(address).first()
    }

    private fun messagesTo(address: String): List<MailpitContainer.Message> =
        mailpit.messages().filter { address in it.to }

    private fun awaitFailed(accountId: UUID): Map<*, *> {
        var found: Map<*, *>? = null
        await().atMost(DELIVERY_BUDGET).until {
            val page = get("/api/v1/notifications/failed?accountId=$accountId", operator()).body()
            found = (page["items"] as List<*>).firstOrNull() as Map<*, *>?
            found != null
        }
        return found.shouldNotBeNull()
    }

    private fun own(shopper: Shopper): List<Map<*, *>> =
        (get("/api/v1/notifications", shopper.token(jwt)).expectStatus().isOk.body()["items"] as List<*>)
            .map { it as Map<*, *> }

    private fun operator(): String = jwt.tokenFor(UUID.randomUUID(), setOf("operator"))

    private fun get(
        path: String,
        token: String,
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .header("Authorization", "Bearer $token")
            .exchange()

    private fun post(
        path: String,
        token: String,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(path)
            .header("Authorization", "Bearer $token")
            .exchange()

    private fun WebTestClient.ResponseSpec.body(): Map<String, Any?> =
        expectBody(MEMBERS).returnResult().responseBody.orEmpty()

    private fun count(sql: String): Long =
        database
            .sql(sql)
            .map { row -> row.get("n", Long::class.javaObjectType) ?: 0L }
            .one()
            .block(TIMEOUT) ?: 0L

    private companion object {
        fun orderPaid(
            accountId: UUID,
            orderId: UUID,
        ) = EnvelopeFixtures.envelope(
            EventType.OrderPaid,
            mapOf(
                "orderId" to "$orderId",
                "orderNumber" to "ORD-20261002-0001",
                "accountId" to "$accountId",
                "lines" to listOf(mapOf("name" to "Puzzle", "quantity" to 2, "unitPrice" to money(UNIT_PRICE))),
                "total" to money(ORDER_TOTAL),
                "deliveryAddress" to
                    mapOf(
                        "recipientName" to "Ada Lovelace",
                        "line1" to "Rua das Flores 12",
                        "city" to "Sao Paulo",
                        "postalCode" to "01000-000",
                        "country" to "BR",
                    ),
                "orderStatus" to "placed",
                "paymentStatus" to "approved",
            ),
            aggregateId = orderId,
        )

        fun orderShipped(
            accountId: UUID,
            orderId: UUID,
        ) = EnvelopeFixtures.envelope(
            EventType.OrderShipped,
            mapOf("orderId" to "$orderId", "orderNumber" to "ORD-20261002-0001", "accountId" to "$accountId"),
            aggregateId = orderId,
        )

        fun money(amountMinor: Long) = mapOf("amountMinor" to amountMinor, "currency" to "BRL")
    }
}

/** A shopper of one test: a fresh account whose contact the identity stand-in answers. */
private class Shopper(
    val accountId: UUID,
    val email: String,
) {
    fun token(jwt: JwtFixture): String = jwt.tokenFor(accountId, setOf("shopper"))

    companion object {
        fun withEmailOnly(identity: WireMockServer): Shopper =
            fresh().also { IdentityStub.emailOnly(identity, it.accountId, it.email) }

        fun withSms(
            identity: WireMockServer,
            phone: String,
        ): Shopper = fresh().also { IdentityStub.smsOptedIn(identity, it.accountId, it.email, phone) }

        fun anonymised(identity: WireMockServer): Shopper =
            fresh().also { IdentityStub.anonymised(identity, it.accountId) }

        private fun fresh(): Shopper {
            val accountId = UUID.randomUUID()
            return Shopper(accountId, "shopper-$accountId@example.test")
        }
    }
}

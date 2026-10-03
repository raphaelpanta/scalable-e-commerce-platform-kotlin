package com.ecommerce.notification.application

import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.Amount
import com.ecommerce.notification.domain.DeliveryFailure
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.EmailAddress
import com.ecommerce.notification.domain.EventId
import com.ecommerce.notification.domain.EventSource
import com.ecommerce.notification.domain.FailureCategory
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.NotificationId
import com.ecommerce.notification.domain.NotificationKind
import com.ecommerce.notification.domain.OrderId
import com.ecommerce.notification.domain.OrderReference
import com.ecommerce.notification.domain.PhoneNumber
import com.ecommerce.notification.domain.Recipient
import com.ecommerce.notification.domain.RecipientContact
import com.ecommerce.notification.domain.RetryPolicy
import com.ecommerce.notification.domain.SecretLink
import com.ecommerce.notification.domain.TemplateData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.time.Duration
import java.util.UUID

internal const val CORRELATION = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
internal val ORDER =
    OrderReference(OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")), "ORD-20261002-0001")
internal val UNAVAILABLE = DeliveryFailure(FailureCategory.CHANNEL_UNAVAILABLE, "SMTP refused the message (451)")

internal fun contact(
    sms: Boolean = false,
    anonymised: Boolean = false,
): RecipientContact =
    RecipientContact(
        email = EmailAddress.of("ada@example.test").getOrNull(),
        phone = PhoneNumber.of("+5511987654321").getOrNull(),
        phoneVerified = sms,
        channels =
            if (sms) {
                setOf(
                    NotificationChannel.EMAIL,
                    NotificationChannel.SMS,
                )
            } else {
                setOf(NotificationChannel.EMAIL)
            },
        anonymised = anonymised,
    )

internal fun source(account: AccountId = AccountId(UUID.randomUUID())) =
    EventSource(EventId(UUID.randomUUID()), account, CORRELATION)

/** An operator, the only caller of the failed view and of retries. */
internal val OPERATOR =
    Caller(AccountId(UUID.fromString("7d1f9a40-5c2e-4b8a-9e63-0f4d2c8b1a57")), setOf(CallerRole.OPERATOR))

internal fun shipped(
    source: EventSource,
    snapshot: RecipientContact? = null,
) = NotificationTrigger.MessageRequested(source, snapshot, TemplateData.OrderShipped(ORDER))

/** Everything a use-case test needs, wired with fakes that all pass through [gate]. */
internal class World(
    var lookup: ContactLookup = ContactLookup.Found(contact()),
    val email: ScriptedChannel = ScriptedChannel(SendResult.Delivered),
    val text: ScriptedChannel = ScriptedChannel(SendResult.Delivered),
    policy: RetryPolicy = RetryPolicy(),
    val gate: Gate = Gate(),
) {
    val notifications = InMemoryNotifications(gate)
    val recipients = InMemoryRecipients(gate)
    val clock = ManualClock()
    val outcomes = RecordingOutcomes(gate)
    val transactions = DirectTransactions(gate)
    val lookups = mutableListOf<Pair<AccountId, String>>()
    val contacts =
        RecipientLookupPort { account, correlation ->
            gate.pass()
            lookups += account to correlation
            lookup
        }
    val produce = ProduceNotificationFromEvent(notifications, recipients, contacts, clock)
    val deliver =
        AttemptDelivery(
            notifications,
            AttemptDelivery.Channels(email, text),
            contacts,
            outcomes,
            transactions,
            clock,
            AttemptDelivery.Settings(policy, Duration.ofMinutes(1)),
        )
    val retry = RetryFailed(notifications, clock)

    init {
        email.gate = gate
        text.gate = gate
    }

    fun only() = notifications.stored.values.single()

    fun only(predicate: (Notification) -> Boolean) = notifications.stored.values.first(predicate)
}

/**
 * Runs [scenario] once to count the port calls, then once per call with that call failing after it suspended: the
 * failure must reach the caller every time (the consumer transaction rolls back and the event is redelivered).
 */
internal suspend fun everyPortFailurePropagates(
    world: (Gate) -> World,
    scenario: suspend (World) -> Unit,
) {
    val probe = Gate()
    scenario(world(probe))
    (1..probe.calls).forEach { call ->
        shouldThrow<PortFailure> { scenario(world(Gate(failAt = call))) }
    }
}

class ProduceNotificationFromEventSpec :
    FunSpec({
        test("an order event yields one queued notification per permitted channel, from identity's contact") {
            val world = World(lookup = ContactLookup.Found(contact(sms = true)))
            val source = source()
            world.produce(shipped(source)) shouldBe 2
            world.notifications.stored.values
                .map { it.channel } shouldBe
                listOf(NotificationChannel.EMAIL, NotificationChannel.SMS)
            world.notifications.stored.values.forEach {
                it.status shouldBe DeliveryStatus.QUEUED
                it.orderId shouldBe ORDER.orderId
                it.createdAt shouldBe world.clock.now
            }
            world.lookups shouldBe listOf(source.accountId to CORRELATION)
        }

        test("the same eventId twice produces one message and asks identity once (dedupe by eventId)") {
            checkAll(Arb.int(2, 5)) { deliveries ->
                val world = World()
                val trigger = shipped(source())
                val created = (1..deliveries).sumOf { world.produce(trigger) }
                created shouldBe 1
                world.notifications.stored.size shouldBe 1
                world.lookups shouldHaveSize 1
            }
        }

        test("an unknown account yields nothing") {
            val world = World(lookup = ContactLookup.Unknown)
            world.produce(shipped(source(), contact())) shouldBe 0
            world.notifications.stored.values
                .shouldBeEmpty()
        }

        test("when identity cannot answer, the event's recipient snapshot is used") {
            val world = World(lookup = ContactLookup.Unavailable)
            world.produce(shipped(source(), contact(sms = true))) shouldBe 2
        }

        test("when identity cannot answer and there is no snapshot, one email is queued awaiting its recipient") {
            val world = World(lookup = ContactLookup.Unavailable)
            val source = source()
            world.produce(shipped(source)) shouldBe 1
            val queued = world.only()
            queued.awaitingRecipient shouldBe true
            queued.status shouldBe DeliveryStatus.QUEUED
            queued.channel shouldBe NotificationChannel.EMAIL
            queued.recipient.shouldBeNull()
            queued.nextAttemptAt shouldBe world.clock.now
            world.produce(shipped(source)) shouldBe 0
        }

        test("an anonymised account (identity or read model) gets one suppressed record and nothing is sent") {
            val byIdentity = World(lookup = ContactLookup.Found(contact(anonymised = true)))
            byIdentity.produce(shipped(source())) shouldBe 1
            byIdentity.only().status shouldBe DeliveryStatus.SUPPRESSED

            val byReadModel = World(lookup = ContactLookup.Found(contact(sms = true)))
            val account = AccountId(UUID.randomUUID())
            byReadModel.recipients.save(Recipient.registered(account).anonymise())
            byReadModel.produce(shipped(source(account))) shouldBe 1
            byReadModel.only().status shouldBe DeliveryStatus.SUPPRESSED
            byReadModel.lookups.shouldBeEmpty()
            byReadModel.deliver.deliverDue(10) shouldBe 0
        }

        test("AccountRegistered records the recipient and queues the verification email") {
            val world = World()
            val source = source()
            val link = SecretLink.of("http://localhost:8080", "/verify", "token-123")
            val registered =
                NotificationTrigger.AccountRegistered(source, contact(), TemplateData.AccountVerification(link))
            world.produce(registered) shouldBe 1
            world.recipients.stored[source.accountId] shouldBe Recipient.registered(source.accountId)
            world.only().kind shouldBe NotificationKind.ACCOUNT_VERIFICATION
            world.only().orderId.shouldBeNull()

            world.recipients.save(Recipient.registered(source.accountId).verified())
            world.produce(registered.copy(source = source(source.accountId))) shouldBe 1
            world.recipients.stored[source.accountId]?.emailVerified shouldBe true
        }

        test("AccountVerified updates the read model and creates no notification") {
            val world = World()
            val source = source()
            world.produce(NotificationTrigger.AccountVerified(source)) shouldBe 0
            world.recipients.stored[source.accountId] shouldBe Recipient.registered(source.accountId).verified()
            world.notifications.stored.values
                .shouldBeEmpty()
            world.lookups.shouldBeEmpty()
        }

        test("AccountDeleted anonymises the recipient, suppresses queued notifications and forgets addresses") {
            val world = World(lookup = ContactLookup.Found(contact(sms = true)))
            val account = AccountId(UUID.randomUUID())
            world.produce(shipped(source(account)))
            world.produce(shipped(source())) // another account keeps its messages
            world.deliver.deliverDue(1) // the oldest one of the account is sent
            world.produce(NotificationTrigger.AccountDeleted(source(account))) shouldBe 0

            world.recipients.stored[account]?.anonymised shouldBe true
            val mine =
                world.notifications.stored.values
                    .filter { it.accountId == account }
            mine.map { it.status } shouldBe listOf(DeliveryStatus.SENT, DeliveryStatus.SUPPRESSED)
            mine.forEach { it.recipient.shouldBeNull() }
            world.notifications.forgotten shouldBe listOf(account)
            world.notifications.stored.values
                .filter { it.accountId != account }
                .map { it.status } shouldBe listOf(DeliveryStatus.QUEUED, DeliveryStatus.QUEUED)

            world.produce(shipped(source(account))) shouldBe 1
            world.notifications.stored.values
                .last()
                .status shouldBe DeliveryStatus.SUPPRESSED
        }
    })

class AttemptDeliverySpec :
    FunSpec({
        test("a due notification is sent, its attempt recorded and NotificationSent published") {
            val world = World()
            world.produce(shipped(source()))
            world.clock.advance(Duration.ofSeconds(1))
            world.deliver.deliverDue(10) shouldBe 1
            val sent = world.only()
            sent.status shouldBe DeliveryStatus.SENT
            sent.attempts shouldBe 1
            sent.sentAt shouldBe world.clock.now
            world.email.sent
                .single()
                .notificationId shouldBe sent.id
            world.email.sent
                .single()
                .content shouldBe sent.content
            world.email.sent
                .single()
                .to shouldBe sent.recipient
            world.notifications.attempts
                .single()
                .succeeded shouldBe true
            world.outcomes.sent shouldBe listOf(sent)
            world.outcomes.failed.shouldBeEmpty()
            world.deliver.deliverDue(10) shouldBe 0
        }

        test("SMS goes to the SMS channel") {
            val world = World(lookup = ContactLookup.Found(contact(sms = true)))
            world.produce(shipped(source()))
            world.deliver.deliverDue(10) shouldBe 2
            world.email.sent shouldHaveSize 1
            world.text.sent shouldHaveSize 1
            world.text.sent
                .single()
                .to.value shouldBe "+5511987654321"
        }

        test("a failing channel is retried with increasing delay, then failed and published once") {
            val world = World(email = ScriptedChannel(SendResult.Failed(UNAVAILABLE)))
            world.produce(shipped(source()))
            val delays = mutableListOf<Duration>()
            repeat(5) {
                world.deliver.deliverDue(10) shouldBe 1
                val after = world.only()
                after.nextAttemptAt?.let { next -> delays += Duration.between(world.clock.now, next) }
                world.deliver.deliverDue(10) shouldBe 0 // not due yet: waiting for the delay
                world.clock.advance(Duration.ofMinutes(10))
            }
            delays shouldBe listOf(30L, 60L, 120L, 240L).map(Duration::ofSeconds)
            val failed = world.only()
            failed.status shouldBe DeliveryStatus.FAILED
            failed.attempts shouldBe 5
            failed.lastFailure shouldBe UNAVAILABLE
            world.notifications.attempts.map { it.number } shouldBe listOf(1, 2, 3, 4, 5)
            world.notifications.attempts.forEach { it.failure shouldBe UNAVAILABLE }
            world.outcomes.failed shouldBe listOf(failed)
            world.outcomes.sent.shouldBeEmpty()
            world.deliver.deliverDue(10) shouldBe 0
        }

        test("a notification that lost its address fails at once as an invalid recipient") {
            val world = World()
            world.produce(shipped(source()))
            val queued = world.only()
            world.notifications.stored[queued.id] = queued.copy(recipient = null)
            world.deliver.deliverDue(10) shouldBe 1
            world.only().status shouldBe DeliveryStatus.FAILED
            world.only().lastFailure?.category shouldBe FailureCategory.INVALID_RECIPIENT
            world.only().lastFailure?.reason shouldBe NO_ADDRESS
            world.email.sent.shouldBeEmpty()
        }

        test("a notification suppressed while it was being sent stays suppressed and publishes nothing") {
            val world = World()
            world.produce(shipped(source()))
            val id = world.only().id
            val suppressing =
                EmailSenderPort {
                    val current = world.notifications.stored.getValue(id)
                    world.notifications.stored[id] = current.suppress().getOrNull() ?: error("not suppressed")
                    SendResult.Delivered
                }
            val deliver =
                AttemptDelivery(
                    world.notifications,
                    AttemptDelivery.Channels(suppressing, world.text),
                    world.contacts,
                    world.outcomes,
                    world.transactions,
                    world.clock,
                )
            deliver.deliverDue(10) shouldBe 1
            world.only().status shouldBe DeliveryStatus.SUPPRESSED
            world.notifications.attempts.shouldBeEmpty()
            world.outcomes.sent.shouldBeEmpty()
        }

        test("a round claims at most the limit, oldest first, under a lease") {
            val world = World()
            repeat(3) {
                world.produce(shipped(source()))
                world.clock.advance(Duration.ofSeconds(1))
            }
            world.deliver.deliverDue(2) shouldBe 2
            world.notifications.stored.values
                .map { it.status } shouldBe
                listOf(DeliveryStatus.SENT, DeliveryStatus.SENT, DeliveryStatus.QUEUED)
            world.deliver.deliverDue(2) shouldBe 1
            world.transactions.runs shouldBe 5
        }
    })

class RetryAndQueriesSpec :
    FunSpec({
        test("an operator retry re-queues a failed notification, which the next round sends") {
            val failedWorld = World(email = ScriptedChannel(SendResult.Failed(UNAVAILABLE)), policy = RetryPolicy(1))
            failedWorld.produce(shipped(source()))
            failedWorld.deliver.deliverDue(10)
            val failed = failedWorld.only()
            failed.status shouldBe DeliveryStatus.FAILED

            failedWorld.clock.advance(Duration.ofMinutes(5))
            val requeued = failedWorld.retry(OPERATOR, failed.id).getOrNull()
            requeued?.status shouldBe DeliveryStatus.QUEUED
            requeued?.attempts shouldBe 0
            requeued?.nextAttemptAt shouldBe failedWorld.clock.now
            failedWorld.only() shouldBe requeued
            failedWorld.deliver.deliverDue(10) shouldBe 1
            failedWorld.only().status shouldBe DeliveryStatus.FAILED
        }

        test("retrying an unknown notification is not found; any other status is refused") {
            val world = World()
            world.retry(OPERATOR, NotificationId(UUID.randomUUID())).leftOrNull() shouldBe RetryRefusal.NotFound
            world.produce(shipped(source()))
            world.retry(OPERATOR, world.only().id).leftOrNull() shouldBe RetryRefusal.NotFailed(DeliveryStatus.QUEUED)
            world.deliver.deliverDue(10)
            world.retry(OPERATOR, world.only().id).leftOrNull() shouldBe RetryRefusal.NotFailed(DeliveryStatus.SENT)
        }

        test("a retry that loses a race reports the status that won") {
            val world = World(email = ScriptedChannel(SendResult.Failed(UNAVAILABLE)), policy = RetryPolicy(1))
            world.produce(shipped(source()))
            world.deliver.deliverDue(10)
            val failed = world.only()
            val racing =
                object : NotificationRepository by world.notifications {
                    override suspend fun update(
                        notification: Notification,
                        expected: DeliveryStatus,
                    ): Boolean {
                        world.notifications.stored[failed.id] = failed.copy(status = DeliveryStatus.SUPPRESSED)
                        return false
                    }
                }
            RetryFailed(racing, world.clock)(OPERATOR, failed.id).leftOrNull() shouldBe
                RetryRefusal.NotFailed(DeliveryStatus.SUPPRESSED)
            val vanishing =
                object : NotificationRepository by world.notifications {
                    private var reads = 0

                    override suspend fun findById(id: NotificationId) = if (reads++ == 0) failed else null

                    override suspend fun update(
                        notification: Notification,
                        expected: DeliveryStatus,
                    ): Boolean = false
                }
            RetryFailed(vanishing, world.clock)(OPERATOR, failed.id).leftOrNull() shouldBe
                RetryRefusal.NotFailed(DeliveryStatus.FAILED)
        }

        test("listings pass filters and pages through") {
            checkAll(Arb.uuid(), Arb.int(1, 4)) { uuid, count ->
                val world = World()
                val account = AccountId(uuid)
                repeat(count) {
                    world.produce(shipped(source(account)))
                    world.clock.advance(Duration.ofSeconds(1))
                }
                val own = ListOwn(world.notifications)(OwnFilter(account), PageRequest(0, 2))
                own.totalItems shouldBe count.toLong()
                own.items shouldHaveSize minOf(2, count)
                own.items.first().createdAt shouldBe world.clock.now.minusSeconds(1)
                own.page shouldBe 0
                own.size shouldBe 2
                PageRequest(3, 20).offset shouldBe 60L
                ListFailed(world.notifications)(OPERATOR, FailedFilter(account), PageRequest(0, 20))
                    .getOrNull()
                    ?.totalItems shouldBe 0L
            }
        }

        test("amounts in templates are passed through unchanged") {
            val world = World()
            val refund =
                NotificationTrigger.MessageRequested(
                    source(),
                    null,
                    TemplateData.RefundConfirmation(ORDER.orderId, Amount(19800, "BRL")),
                )
            world.produce(refund) shouldBe 1
            world
                .only()
                .content.body
                .contains("BRL 198.00") shouldBe true
        }
    })

class PortFailureSpec :
    FunSpec({
        val failing = { ScriptedChannel(SendResult.Failed(UNAVAILABLE)) }

        test("producing from an order event propagates every port failure") {
            everyPortFailurePropagates(
                { World(lookup = ContactLookup.Found(contact(sms = true)), gate = it) },
            ) { world ->
                world.produce(shipped(source())) shouldBe 2
            }
        }

        test("producing from the event snapshot propagates every port failure") {
            everyPortFailurePropagates({ World(lookup = ContactLookup.Unavailable, gate = it) }) { world ->
                world.produce(shipped(source(), contact())) shouldBe 1
            }
        }

        test("account events propagate every port failure") {
            everyPortFailurePropagates({ World(gate = it) }) { world ->
                val account = AccountId(UUID.randomUUID())
                val link = SecretLink.of("http://localhost:8080", "/verify", "token")
                val registered =
                    NotificationTrigger.AccountRegistered(source(account), null, TemplateData.AccountVerification(link))
                world.produce(registered) shouldBe 1
                world.produce(NotificationTrigger.AccountVerified(source(account))) shouldBe 0
                world.produce(shipped(source(account))) shouldBe 1
                world.produce(NotificationTrigger.AccountDeleted(source(account))) shouldBe 0
                world.notifications.stored.values
                    .map { it.status } shouldBe listOf(DeliveryStatus.SUPPRESSED, DeliveryStatus.SUPPRESSED)
            }
        }

        test("delivery rounds propagate every port failure") {
            everyPortFailurePropagates({ World(gate = it) }) { world ->
                world.produce(shipped(source()))
                world.deliver.deliverDue(10) shouldBe 1
                world.only().status shouldBe DeliveryStatus.SENT
            }
            everyPortFailurePropagates({ World(email = failing(), policy = RetryPolicy(1), gate = it) }) { world ->
                world.produce(shipped(source()))
                world.deliver.deliverDue(10) shouldBe 1
                world.only().status shouldBe DeliveryStatus.FAILED
            }
            everyPortFailurePropagates({ World(email = failing(), gate = it) }) { world ->
                world.produce(shipped(source()))
                world.deliver.deliverDue(10) shouldBe 1
                world.only().status shouldBe DeliveryStatus.QUEUED
            }
        }

        test("operator retries and listings propagate every port failure") {
            everyPortFailurePropagates({ World(email = failing(), policy = RetryPolicy(1), gate = it) }) { world ->
                world.produce(shipped(source()))
                world.deliver.deliverDue(10)
                world.retry(OPERATOR, world.only().id).isRight() shouldBe true
                world.retry(OPERATOR, NotificationId(UUID.randomUUID())).leftOrNull() shouldBe RetryRefusal.NotFound
                val page = PageRequest(0, 20)
                ListOwn(world.notifications)(OwnFilter(world.only().accountId), page).totalItems shouldBe 1L
                ListFailed(world.notifications)(OPERATOR, FailedFilter(), page).getOrNull()?.totalItems shouldBe 0L
            }
        }

        test("filters and pages reach the repository") {
            val world = World(email = failing(), policy = RetryPolicy(1))
            val account = AccountId(UUID.randomUUID())
            repeat(3) {
                world.produce(shipped(source(account)))
                world.clock.advance(Duration.ofSeconds(1))
            }
            world.deliver.deliverDue(10) shouldBe 3
            val failedAt = world.only { it.accountId == account }.failedAt
            val all = PageRequest(0, 20)
            ListOwn(world.notifications)(OwnFilter(account, NotificationChannel.SMS), all).totalItems shouldBe 0L
            val byKind = OwnFilter(account, kind = NotificationKind.ORDER_DELIVERED)
            ListOwn(world.notifications)(byKind, all).totalItems shouldBe 0L
            val second = ListOwn(world.notifications)(OwnFilter(account), PageRequest(1, 2))
            second.page shouldBe 1
            second.items shouldHaveSize 1
            val listFailed = ListFailed(world.notifications)

            suspend fun failed(
                filter: FailedFilter,
                page: PageRequest,
            ): Page<Notification> = listFailed(OPERATOR, filter, page).getOrNull() ?: error("forbidden")
            failed(FailedFilter(account), PageRequest(1, 2)).page shouldBe 1
            failed(FailedFilter(channel = NotificationChannel.SMS), all).totalItems shouldBe 0L
            failed(FailedFilter(kind = NotificationKind.ORDER_SHIPPED), all).totalItems shouldBe 3L
            failed(FailedFilter(failedFrom = failedAt?.plusSeconds(1)), all).totalItems shouldBe 0L
            failed(FailedFilter(failedTo = failedAt), all).totalItems shouldBe 0L
            failed(FailedFilter(failedFrom = failedAt, failedTo = failedAt?.plusSeconds(1)), all).totalItems shouldBe 3L
        }

        test("a registration whose identity lookup fails uses the event snapshot") {
            val world = World(lookup = ContactLookup.Unavailable)
            val link = SecretLink.of("http://localhost:8080", "/verify", "token")
            val registered =
                NotificationTrigger.AccountRegistered(source(), contact(), TemplateData.AccountVerification(link))
            world.produce(registered) shouldBe 1
            registered.snapshot shouldBe contact()
            world.only().awaitingRecipient shouldBe false
            world.produce(registered.copy(source = source(), snapshot = null)) shouldBe 1
            world.notifications.stored.values
                .map { it.awaitingRecipient } shouldBe listOf(false, true)
        }

        test("a deferred recipient propagates every port failure, from production to delivery") {
            everyPortFailurePropagates({ World(lookup = ContactLookup.Unavailable, gate = it) }) { world ->
                world.produce(shipped(source())) shouldBe 1
                world.deliver.deliverDue(10) shouldBe 1
                world.lookup = ContactLookup.Found(contact())
                world.clock.advance(Duration.ofMinutes(1))
                world.deliver.deliverDue(10) shouldBe 1
                world.only().status shouldBe DeliveryStatus.SENT
            }
        }
    })

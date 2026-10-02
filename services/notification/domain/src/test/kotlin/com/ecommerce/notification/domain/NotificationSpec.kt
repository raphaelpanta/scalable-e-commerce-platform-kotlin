package com.ecommerce.notification.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

private const val CORRELATION = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
private val notTerminal = Arb.element(DeliveryStatus.SENT, DeliveryStatus.FAILED, DeliveryStatus.SUPPRESSED)
private val notFailed = Arb.element(DeliveryStatus.QUEUED, DeliveryStatus.SENT, DeliveryStatus.SUPPRESSED)

class NotificationSpec :
    FunSpec({
        val policy = RetryPolicy()

        context("delivery status transitions") {
            test("a successful attempt moves queued to sent and counts the attempt") {
                checkAll(DomainArbs.notification(attempts = 2), DomainArbs.instant) { queued, at ->
                    val sent = queued.recordSuccess(at).getOrNull()
                    sent?.status shouldBe DeliveryStatus.SENT
                    sent?.attempts shouldBe 3
                    sent?.sentAt shouldBe at
                    sent?.lastAttemptAt shouldBe at
                    sent?.nextAttemptAt.shouldBeNull()
                }
            }

            test("a failed attempt with budget left stays queued and waits the retry delay") {
                checkAll(Arb.int(0, 3), DomainArbs.instant, DomainArbs.failure) { made, at, failure ->
                    if (!failure.permanent) {
                        val queued = DomainArbs.notification(attempts = made).one()
                        val retried = queued.recordFailure(at, failure, policy).getOrNull()
                        retried?.status shouldBe DeliveryStatus.QUEUED
                        retried?.attempts shouldBe made + 1
                        retried?.nextAttemptAt shouldBe at.plus(policy.delayAfter(made + 1))
                        retried?.lastFailure shouldBe failure
                        retried?.lastAttemptAt shouldBe at
                        retried?.failedAt.shouldBeNull()
                    }
                }
            }

            test("the fifth failed attempt moves the notification to failed") {
                checkAll(DomainArbs.notification(attempts = 4), DomainArbs.instant, DomainArbs.failure) { q, at, f ->
                    val failed = q.recordFailure(at, f, policy).getOrNull()
                    failed?.status shouldBe DeliveryStatus.FAILED
                    failed?.attempts shouldBe 5
                    failed?.failedAt shouldBe at
                    failed?.lastFailure shouldBe f
                    failed?.nextAttemptAt.shouldBeNull()
                }
            }

            test("a permanent failure fails at once") {
                val invalid = DeliveryFailure(FailureCategory.INVALID_RECIPIENT, "no address")
                checkAll(DomainArbs.notification(), DomainArbs.instant) { queued, at ->
                    val failed = queued.recordFailure(at, invalid, policy).getOrNull()
                    failed?.status shouldBe DeliveryStatus.FAILED
                    failed?.attempts shouldBe 1
                }
                invalid.permanent shouldBe true
                DeliveryFailure(FailureCategory.CHANNEL_UNAVAILABLE, "x").permanent shouldBe false
                DeliveryFailure(FailureCategory.REJECTED_BY_PROVIDER, "x").permanent shouldBe false
            }

            test("sent, failed and suppressed refuse every delivery transition") {
                checkAll(notTerminal, DomainArbs.instant, DomainArbs.failure) { status, at, failure ->
                    val notification = DomainArbs.notification(status).one()
                    notification.recordSuccess(at).leftOrNull() shouldBe
                        TransitionRefused(status, "record a successful attempt")
                    notification.recordFailure(at, failure, policy).leftOrNull() shouldBe
                        TransitionRefused(status, "record a failed attempt")
                    notification.suppress().leftOrNull() shouldBe TransitionRefused(status, "suppress")
                }
            }

            test("only a queued notification can be suppressed") {
                checkAll(DomainArbs.notification()) { queued ->
                    val suppressed = queued.copy(nextAttemptAt = queued.createdAt).suppress().getOrNull()
                    suppressed?.status shouldBe DeliveryStatus.SUPPRESSED
                    suppressed?.nextAttemptAt.shouldBeNull()
                }
            }

            test("an operator retry re-queues a failed notification with a fresh budget") {
                checkAll(DomainArbs.notification(attempts = 4), DomainArbs.instant, DomainArbs.failure) { q, at, f ->
                    val failed = q.recordFailure(at, f, policy).getOrNull()
                    val later = at.plusSeconds(60)
                    val requeued = failed?.requeue(later)?.getOrNull()
                    requeued?.status shouldBe DeliveryStatus.QUEUED
                    requeued?.attempts shouldBe 0
                    requeued?.nextAttemptAt shouldBe later
                    requeued?.lastFailure.shouldBeNull()
                    requeued?.lastAttemptAt.shouldBeNull()
                    requeued?.failedAt.shouldBeNull()
                }
            }

            test("a notification that is not failed cannot be retried") {
                checkAll(notFailed, DomainArbs.instant) { status, at ->
                    DomainArbs
                        .notification(status)
                        .one()
                        .requeue(at)
                        .leftOrNull() shouldBe
                        TransitionRefused(status, "retry")
                }
            }

            test("the attempt history records each new attempt with its failure") {
                checkAll(
                    DomainArbs.notification(),
                    DomainArbs.instant,
                    DomainArbs.transientFailure,
                ) { queued, at, failure ->
                    val sent = queued.recordSuccess(at).getOrNull() ?: error("not sent")
                    DeliveryAttempt.between(queued, sent, at) shouldBe DeliveryAttempt(queued.id, 1, at, null)
                    val retried = queued.recordFailure(at, failure, policy).getOrNull() ?: error("not recorded")
                    val attempt = DeliveryAttempt.between(queued, retried, at)
                    attempt shouldBe DeliveryAttempt(queued.id, 1, at, failure)
                    attempt?.succeeded shouldBe false
                    DeliveryAttempt.between(queued, queued, at).shouldBeNull()
                    val sentAfterFailure = retried.recordSuccess(at).getOrNull() ?: error("not sent")
                    DeliveryAttempt.between(retried, sentAfterFailure, at)?.succeeded shouldBe true
                }
            }

            test("the log-safe summary names ids and state, never the recipient or the content") {
                checkAll(DomainArbs.notification(), DomainArbs.email) { notification, email ->
                    val withAddress =
                        notification.copy(
                            recipient = RecipientAddress(email.value),
                            content = MessageContent("Subject", "secret body"),
                        )
                    withAddress.toString() shouldContain notification.id.toString()
                    withAddress.toString() shouldNotContain email.value
                    withAddress.toString() shouldNotContain "secret body"
                    withAddress.dedupeKey shouldBe
                        DedupeKey(notification.sourceEventId, notification.kind, notification.channel)
                }
            }
        }

        context("retry schedule") {
            test("5 attempts, exponential from 30 s") {
                policy.schedule() shouldContainExactly
                    listOf(30L, 60L, 120L, 240L).map(Duration::ofSeconds)
                policy.exhausted(4) shouldBe false
                policy.exhausted(5) shouldBe true
                policy.exhausted(6) shouldBe true
            }

            test("delays never decrease and are capped at 10 minutes") {
                checkAll(Arb.int(1, 200)) { attempt ->
                    val delay = policy.delayAfter(attempt)
                    (delay <= Duration.ofMinutes(10)) shouldBe true
                    (policy.delayAfter(attempt + 1) >= delay) shouldBe true
                }
                policy.delayAfter(6) shouldBe Duration.ofMinutes(10)
                policy.delayAfter(5) shouldBe Duration.ofSeconds(480)
                policy.delayAfter(1_000) shouldBe Duration.ofMinutes(10)
            }

            test("a custom schedule doubles from its initial delay up to its cap") {
                checkAll(Arb.long(1, 1_000), Arb.int(2, 8)) { initialMillis, attempts ->
                    val initial = Duration.ofMillis(initialMillis)
                    val custom = RetryPolicy(attempts, initial, initial.multipliedBy(4))
                    custom.schedule() shouldHaveSize attempts - 1
                    custom.delayAfter(1) shouldBe initial
                    custom.delayAfter(2) shouldBe initial.multipliedBy(2)
                    custom.delayAfter(3) shouldBe initial.multipliedBy(4)
                    custom.delayAfter(4) shouldBe initial.multipliedBy(4)
                    custom.schedule() shouldBeSortedWith Comparator.naturalOrder()
                }
                RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(1), 1).schedule() shouldContainExactly
                    listOf(Duration.ofSeconds(1), Duration.ofSeconds(1))
            }

            test("invalid schedules are refused") {
                shouldThrow<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
                shouldThrow<IllegalArgumentException> { RetryPolicy(initialDelay = Duration.ZERO) }
                shouldThrow<IllegalArgumentException> {
                    RetryPolicy(initialDelay = Duration.ofMinutes(2), maxDelay = Duration.ofMinutes(1))
                }
                shouldThrow<IllegalArgumentException> { RetryPolicy(multiplier = 0) }
                shouldThrow<IllegalArgumentException> { policy.delayAfter(0) }
                RetryPolicy(maxAttempts = 1).schedule().shouldBeEmpty()
                RetryPolicy(initialDelay = Duration.ofMinutes(10)).delayAfter(1) shouldBe Duration.ofMinutes(10)
            }
        }

        context("channel selection by preferences and verified phone") {
            test("an anonymised account or one without an email gets one suppressed email only") {
                checkAll(DomainArbs.contact, Arb.enum<NotificationKind>()) { contact, kind ->
                    if (contact.anonymised || contact.email == null) {
                        ChannelSelection.select(kind, contact) shouldBe
                            listOf(ChannelDecision.Suppress(NotificationChannel.EMAIL))
                    }
                }
            }

            test("email when allowed, and always for verification and password reset") {
                checkAll(DomainArbs.contact, Arb.enum<NotificationKind>()) { contact, kind ->
                    val email = contact.email
                    if (!contact.anonymised && email != null) {
                        val decisions = ChannelSelection.select(kind, contact)
                        val byEmail = decisions.filter { it.channel == NotificationChannel.EMAIL }
                        val expected = kind.security || NotificationChannel.EMAIL in contact.channels
                        val deliver = ChannelDecision.Deliver(NotificationChannel.EMAIL, RecipientAddress(email.value))
                        byEmail shouldBe if (expected) listOf(deliver) else emptyList()
                        decisions.firstOrNull()?.channel?.let { if (expected) it shouldBe NotificationChannel.EMAIL }
                    }
                }
            }

            test("SMS only when opted in with a verified phone, never for security kinds") {
                checkAll(DomainArbs.contact, Arb.enum<NotificationKind>()) { contact, kind ->
                    val phone = contact.phone
                    val sms =
                        ChannelSelection.select(kind, contact).filter { it.channel == NotificationChannel.SMS }
                    val expected =
                        !contact.anonymised && contact.email != null && !kind.security &&
                            contact.phoneVerified && phone != null && NotificationChannel.SMS in contact.channels
                    val deliver =
                        phone?.let { ChannelDecision.Deliver(NotificationChannel.SMS, RecipientAddress(it.value)) }
                    sms shouldBe if (expected) listOfNotNull(deliver) else emptyList()
                }
            }

            test("an order shipped to an opted-in shopper goes to both email and SMS (US6.2)") {
                val contact =
                    RecipientContact(
                        DomainArbs.email.one(),
                        DomainArbs.phone.one(),
                        true,
                        setOf(NotificationChannel.EMAIL, NotificationChannel.SMS),
                        false,
                    )
                ChannelSelection.select(NotificationKind.ORDER_SHIPPED, contact).map { it.channel } shouldBe
                    listOf(NotificationChannel.EMAIL, NotificationChannel.SMS)
                val unverified = contact.copy(phoneVerified = false)
                ChannelSelection.select(NotificationKind.ORDER_SHIPPED, unverified).map { it.channel } shouldBe
                    listOf(NotificationChannel.EMAIL)
            }
        }

        context("planning and deduplication by eventId") {
            test("an event already produced yields nothing new; a fresh one yields one notification per channel") {
                checkAll(DomainArbs.templateData, DomainArbs.contact, DomainArbs.instant) { data, contact, at ->
                    val source = EventSource(DomainArbs.eventId.one(), DomainArbs.accountId.one(), CORRELATION)
                    val first = NotificationPlanner.plan(source, data, contact, null, emptySet(), at)
                    first.map { it.channel } shouldBe ChannelSelection.select(data.kind, contact).map { it.channel }
                    first.map { it.dedupeKey }.toSet() shouldHaveSize first.size
                    val produced = first.map { it.dedupeKey }.toSet()
                    NotificationPlanner.plan(source, data, contact, null, produced, at).shouldBeEmpty()
                    first.forEach { notification ->
                        notification.sourceEventId shouldBe source.eventId
                        notification.accountId shouldBe source.accountId
                        notification.correlationId shouldBe CORRELATION
                        notification.kind shouldBe data.kind
                        notification.orderId shouldBe Templates.orderOf(data)
                        notification.attempts shouldBe 0
                        notification.createdAt shouldBe at
                    }
                }
            }

            test("delivered channels start queued and due now; suppressed ones keep no body and no address") {
                checkAll(DomainArbs.templateData, DomainArbs.contact, DomainArbs.instant) { data, contact, at ->
                    val source = EventSource(DomainArbs.eventId.one(), DomainArbs.accountId.one(), CORRELATION)
                    NotificationPlanner.plan(source, data, contact, null, emptySet(), at).forEach { notification ->
                        if (notification.status == DeliveryStatus.SUPPRESSED) {
                            notification.recipient.shouldBeNull()
                            notification.content.body shouldBe ""
                            notification.nextAttemptAt.shouldBeNull()
                        } else {
                            notification.status shouldBe DeliveryStatus.QUEUED
                            notification.nextAttemptAt shouldBe at
                            notification.content shouldBe Templates.render(data, notification.channel)
                        }
                    }
                }
            }

            test("a recipient anonymised in the read model receives nothing, whatever identity says") {
                checkAll(DomainArbs.templateData, DomainArbs.contact, DomainArbs.accountId) { data, contact, account ->
                    val source = EventSource(DomainArbs.eventId.one(), account, CORRELATION)
                    val deleted = Recipient.registered(account).anonymise()
                    val at = DomainArbs.instant.one()
                    val planned = NotificationPlanner.plan(source, data, contact, deleted, emptySet(), at)
                    planned.map { it.status } shouldBe listOf(DeliveryStatus.SUPPRESSED)
                    val active = Recipient.registered(account).verified()
                    NotificationPlanner
                        .plan(source, data, contact, active, emptySet(), at)
                        .map { it.channel } shouldBe ChannelSelection.select(data.kind, contact).map { it.channel }
                }
            }
        }

        context("recipient read model") {
            test("registration, verification and anonymisation") {
                checkAll(DomainArbs.accountId) { account ->
                    val registered = Recipient.registered(account)
                    registered shouldBe Recipient(account, emailVerified = false, anonymised = false)
                    registered.verified() shouldBe Recipient(account, emailVerified = true, anonymised = false)
                    val deleted = registered.verified().anonymise()
                    deleted shouldBe Recipient(account, emailVerified = false, anonymised = true)
                    deleted.verified() shouldBe deleted
                }
            }
        }

        context("identifiers and names") {
            test("every enumeration reads back its wire names and rejects unknown ones") {
                NotificationChannel.entries.forEach { NotificationChannel.fromWire(it.wire) shouldBe it }
                NotificationKind.entries.forEach {
                    NotificationKind.fromWire(it.wire) shouldBe it
                    NotificationKind.fromApiType(it.apiType) shouldBe it
                }
                DeliveryStatus.entries.forEach { DeliveryStatus.fromWire(it.wire) shouldBe it }
                NotificationChannel.fromWire("fax").shouldBeNull()
                NotificationKind.fromWire("registration_verification").shouldBeNull()
                NotificationKind.fromApiType("account_verification").shouldBeNull()
                DeliveryStatus.fromWire("bounced").shouldBeNull()
                NotificationKind.entries.filter { it.security } shouldBe
                    listOf(NotificationKind.ACCOUNT_VERIFICATION, NotificationKind.PASSWORD_RESET)
                NotificationKind.ORDER_CONFIRMATION.template shouldBe "order-confirmation"
            }

            test("ids print their UUID and random ids differ") {
                val uuid = UUID.randomUUID()
                listOf(NotificationId(uuid), AccountId(uuid), EventId(uuid), OrderId(uuid)).forEach {
                    it.toString() shouldBe uuid.toString()
                }
                (NotificationId.random() == NotificationId.random()) shouldBe false
            }
        }
    })

class ContactSpec :
    FunSpec({
        test("valid email addresses are trimmed, lower-cased and masked") {
            checkAll(DomainArbs.emailText) { raw ->
                val email = EmailAddress.of("  ${raw.uppercase()} ").getOrNull()
                email?.value shouldBe raw
                email.toString() shouldBe "***@" + raw.substringAfter('@')
            }
        }

        test("each email rule has its own reason") {
            EmailAddress.of("a".repeat(250) + "@x.io").leftOrNull() shouldBe "email must be at most 254 characters"
            EmailAddress.of("a".repeat(249) + "@x.io").isRight() shouldBe true
            EmailAddress.of("ada lovelace@example.com").leftOrNull() shouldBe "email must not contain whitespace"
            EmailAddress.of("ada@exa\u0007mple.com").leftOrNull() shouldBe "email must not contain whitespace"
            EmailAddress.of("ada.example.com").leftOrNull() shouldBe "email must contain exactly one @"
            EmailAddress.of("a@b@example.com").leftOrNull() shouldBe "email must contain exactly one @"
            EmailAddress.of("@example.com").leftOrNull() shouldBe "email must have a local part"
            EmailAddress.of("ada@example").leftOrNull() shouldBe "email must have a domain containing a dot"
            EmailAddress.of("ada@.example").leftOrNull() shouldBe "email must have a domain containing a dot"
            EmailAddress.of("ada@example.").leftOrNull() shouldBe "email must have a domain containing a dot"
            EmailAddress.of("anon-4f9c2d71@anonymised.invalid").isRight() shouldBe true
        }

        test("phone numbers are E.164 and masked") {
            checkAll(DomainArbs.phoneText) { raw ->
                val phone = PhoneNumber.of(" $raw ").getOrNull()
                phone?.value shouldBe raw
                phone?.digits shouldBe raw.removePrefix("+")
                phone.toString() shouldNotContain raw
            }
            listOf("5511987654321", "+0511987654321", "+1234567", "+1234567890123456", "+55 11 98765").forEach {
                PhoneNumber.of(it).leftOrNull() shouldBe "phone must be in E.164 format"
            }
            PhoneNumber.of("+12345678").isRight() shouldBe true
            PhoneNumber.of("+123456789012345").isRight() shouldBe true
        }

        test("recipient addresses never print") {
            checkAll(DomainArbs.email) { email ->
                RecipientAddress(email.value).toString() shouldNotContain email.value
            }
        }
    })

class TemplatesSpec :
    FunSpec({
        test("links carry the URL-encoded token; summaries and content descriptions never show it") {
            checkAll(DomainArbs.token, DomainArbs.notification()) { token, notification ->
                val raw = "$token+/=?"
                val link = SecretLink.of("http://localhost:8080/", "/verify", raw)
                val encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8)
                link.value shouldBe "http://localhost:8080/verify?token=$encoded"
                link.toString() shouldBe "http://localhost:8080/verify?token=***"
                val data = TemplateData.AccountVerification(link)
                val content = Templates.render(data, NotificationChannel.EMAIL)
                content.body shouldContain link.value
                content.subject shouldNotContain token
                content.toString() shouldNotContain token
                data.toString() shouldNotContain token
                TemplateData.PasswordReset(link).toString() shouldNotContain token
                notification.copy(content = content).toString() shouldNotContain token
                Templates.render(data, NotificationChannel.SMS).body shouldNotContain token
                link shouldBe SecretLink.of("http://localhost:8080", "/verify", raw)
                link.hashCode() shouldBe SecretLink.of("http://localhost:8080", "/verify", raw).hashCode()
                (link == SecretLink.of("http://localhost:8080", "/verify", token)) shouldBe false
            }
        }

        test("the order confirmation states order number and id, lines, total and delivery address (US6.1)") {
            checkAll(DomainArbs.templateData) { data ->
                if (data is TemplateData.OrderConfirmation) {
                    val content = Templates.render(data, NotificationChannel.EMAIL)
                    content.subject shouldBe "Order ${data.order.orderNumber} confirmed"
                    content.body shouldContain data.order.orderNumber
                    content.body shouldContain data.order.orderId.toString()
                    data.lines.forEach { line ->
                        content.body shouldContain "- ${line.quantity} x ${line.name} at ${line.unitPrice.formatted()}"
                    }
                    content.body shouldContain "Total: ${data.total.formatted()}"
                    data.address.lines().forEach { content.body shouldContain it }
                    data.toString() shouldNotContain data.address.line1
                    Templates.render(data, NotificationChannel.SMS).body shouldBe
                        "Order ${data.order.orderNumber} confirmed, total ${data.total.formatted()}."
                }
            }
        }

        test("every kind names its order and renders a subject and a body on both channels") {
            checkAll(DomainArbs.templateData) { data ->
                val email = Templates.render(data, NotificationChannel.EMAIL)
                val sms = Templates.render(data, NotificationChannel.SMS)
                sms.subject shouldBe email.subject
                (sms.body.length < email.body.length) shouldBe true
                when (data) {
                    is TemplateData.AccountVerification, is TemplateData.PasswordReset -> {
                        Templates.orderOf(data).shouldBeNull()
                        sms.body shouldBe "Check your email to continue."
                    }

                    is TemplateData.RefundConfirmation -> {
                        Templates.orderOf(data) shouldBe data.orderId
                        email.body shouldContain data.amount.formatted()
                        email.body shouldContain data.orderId.toString()
                        sms.body shouldContain data.amount.formatted()
                    }

                    else -> {
                        Templates.orderOf(data)?.let { email.body shouldContain it.toString() }
                    }
                }
            }
        }

        test("each kind has its own wording") {
            val order = OrderReference(OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")), "ORD-1")
            val total = Amount(4913, "BRL")
            val failure = TemplateData.PaymentFailure(order, total, "insufficient_funds")
            Templates.render(failure, NotificationChannel.EMAIL).let {
                it.subject shouldBe "Payment failed for order ORD-1"
                it.body shouldContain "Reason: insufficient funds."
                it.body shouldContain "BRL 49.13"
            }
            Templates.render(failure, NotificationChannel.SMS).body shouldBe
                "Payment for order ORD-1 failed: insufficient funds."
            Templates.render(TemplateData.OrderShipped(order), NotificationChannel.EMAIL).subject shouldBe
                "Order ORD-1 has shipped"
            Templates.render(TemplateData.OrderShipped(order), NotificationChannel.SMS).body shouldBe
                "Order ORD-1 has shipped."
            Templates.render(TemplateData.OrderDelivered(order), NotificationChannel.EMAIL).subject shouldBe
                "Order ORD-1 was delivered"
            Templates.render(TemplateData.OrderDelivered(order), NotificationChannel.SMS).body shouldBe
                "Order ORD-1 was delivered."
            val refunded = TemplateData.OrderCancelled(order, total, "SHOPPER_REQUEST", true)
            Templates.render(refunded, NotificationChannel.EMAIL).let {
                it.subject shouldBe "Order ORD-1 was cancelled"
                it.body shouldContain "you asked for the cancellation"
                it.body shouldContain "A refund of BRL 49.13 follows."
            }
            Templates.render(refunded.copy(refundRequired = false), NotificationChannel.EMAIL).body shouldContain
                "Nothing was charged."
            Templates.render(refunded, NotificationChannel.SMS).body shouldBe "Order ORD-1 was cancelled."
            Templates
                .render(TemplateData.RefundConfirmation(order.orderId, total), NotificationChannel.EMAIL)
                .subject shouldBe "Refund recorded"
            Templates
                .render(TemplateData.RefundConfirmation(order.orderId, total), NotificationChannel.SMS)
                .body shouldBe "Refund of BRL 49.13 recorded."
            val reset = TemplateData.PasswordReset(SecretLink.of("http://h", "/reset-password", "tok"))
            Templates.render(reset, NotificationChannel.EMAIL).let {
                it.subject shouldBe "Reset your password"
                it.body shouldContain "http://h/reset-password?token=tok"
            }
            val verification = TemplateData.AccountVerification(SecretLink.of("http://h", "/verify", "t"))
            Templates.render(verification, NotificationChannel.EMAIL).subject shouldBe "Verify your email address"
        }

        test("cancellation reasons and decline categories read as text") {
            Templates.cancellationReason("SHOPPER_REQUEST") shouldBe "you asked for the cancellation"
            Templates.cancellationReason("OPERATOR") shouldBe "our team cancelled it"
            Templates.cancellationReason("PAYMENT_FAILED") shouldBe "the payment failed"
            Templates.cancellationReason("PAYMENT_EXPIRED") shouldBe "the payment was not confirmed in time"
            Templates.cancellationReason("SOMETHING_NEW") shouldBe "it can no longer be fulfilled"
            Templates.humanise("card_expired") shouldBe "card expired"
        }

        test("amounts print in major units with two decimals; negative amounts are refused") {
            Amount(4000, "BRL").formatted() shouldBe "BRL 40.00"
            Amount(5, "BRL").formatted() shouldBe "BRL 0.05"
            Amount(19800, "BRL").formatted() shouldBe "BRL 198.00"
            Amount(0, "BRL").formatted() shouldBe "BRL 0.00"
            checkAll(Arb.long(0, 1_000_000_000)) { minor ->
                val printed = Amount(minor, "BRL").formatted()
                printed.removePrefix("BRL ").replace(".", "").toLong() shouldBe minor
            }
            shouldThrow<IllegalArgumentException> { Amount(-1, "BRL") }
        }

        test("addresses print as message lines and never in summaries") {
            val address = AddressSummary("Ada Lovelace", "12 Analytical Street", null, "London", "N1 9GU", "GB")
            address.lines() shouldBe listOf("Ada Lovelace", "12 Analytical Street", "N1 9GU London", "GB")
            address.copy(line2 = "Flat 2").lines() shouldBe
                listOf("Ada Lovelace", "12 Analytical Street", "Flat 2", "N1 9GU London", "GB")
            address.toString() shouldBe "AddressSummary(***)"
            OrderReference(OrderId(UUID.fromString("0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")), "ORD-1").label() shouldBe
                "ORD-1 (order id 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10)"
        }
    })

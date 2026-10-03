package com.ecommerce.notification.application

import com.ecommerce.notification.domain.DeliveryFailure
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.FailureCategory
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.NotificationKind
import com.ecommerce.notification.domain.RetryPolicy
import com.ecommerce.notification.domain.SecretLink
import com.ecommerce.notification.domain.TemplateData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.time.Duration

private val LINK = SecretLink.of("http://localhost:8080", "/verify", "token-123")
private val CONTACT_DOWN = DeliveryFailure(FailureCategory.CHANNEL_UNAVAILABLE, CONTACT_UNAVAILABLE)

/** The identity-borne messages that carry a single-use link: they must never be lost (T158). */
private fun securityTrigger(kind: NotificationKind): NotificationTrigger =
    if (kind == NotificationKind.ACCOUNT_VERIFICATION) {
        NotificationTrigger.AccountRegistered(source(), null, TemplateData.AccountVerification(LINK))
    } else {
        NotificationTrigger.MessageRequested(source(), null, TemplateData.PasswordReset(LINK))
    }

/**
 * T158 (FR-018, FR-019): identity is down and the event carries no recipient snapshot. The event is consumed (not
 * dead-lettered): its message is queued awaiting its recipient, and the delivery job resolves the contact first.
 */
class DeferredRecipientSpec :
    FunSpec({
        test("AccountRegistered and PasswordResetRequested are queued, then sent once identity answers") {
            checkAll(
                Arb.element(NotificationKind.ACCOUNT_VERIFICATION, NotificationKind.PASSWORD_RESET),
                Arb.int(0, 3),
            ) { kind, outageRounds ->
                val world = World(lookup = ContactLookup.Unavailable)
                world.produce(securityTrigger(kind)) shouldBe 1
                repeat(outageRounds) {
                    world.deliver.deliverDue(10) shouldBe 1
                    world.only().lastFailure shouldBe CONTACT_DOWN
                    world.clock.advance(Duration.ofMinutes(10))
                }
                // Email switched off in the preferences: security messages still go by email.
                world.lookup = ContactLookup.Found(contact().copy(channels = emptySet()))
                world.deliver.deliverDue(10) shouldBe 1

                val sent = world.only()
                sent.status shouldBe DeliveryStatus.SENT
                sent.kind shouldBe kind
                sent.awaitingRecipient shouldBe false
                sent.attempts shouldBe outageRounds + 1
                sent.recipient?.value shouldBe "ada@example.test"
                world.email.sent
                    .single()
                    .to.value shouldBe "ada@example.test"
                world.email.sent
                    .single()
                    .content.body
                    .contains(LINK.value) shouldBe true
                world.notifications.attempts.map { it.failure } shouldBe List(outageRounds) { CONTACT_DOWN } + null
                world.outcomes.sent shouldBe listOf(sent)
                world.lookups shouldHaveSize outageRounds + 2
            }
        }

        test("an outage longer than the retry budget fails the message, operators see it and their retry delivers") {
            val world = World(lookup = ContactLookup.Unavailable, policy = RetryPolicy(2))
            world.produce(shipped(source())) shouldBe 1
            world.deliver.deliverDue(10) shouldBe 1
            world.clock.advance(Duration.ofMinutes(10))
            world.deliver.deliverDue(10) shouldBe 1

            val failed = world.only()
            failed.status shouldBe DeliveryStatus.FAILED
            failed.lastFailure shouldBe CONTACT_DOWN
            failed.awaitingRecipient shouldBe true
            world.outcomes.failed shouldBe listOf(failed)
            ListFailed(world.notifications)(OPERATOR, FailedFilter(), PageRequest(0, 20))
                .getOrNull()
                ?.items shouldBe listOf(failed)

            world.retry(OPERATOR, failed.id).getOrNull()?.awaitingRecipient shouldBe true
            world.lookup = ContactLookup.Found(contact())
            world.deliver.deliverDue(10) shouldBe 1
            world.only().status shouldBe DeliveryStatus.SENT
            world.email.sent shouldHaveSize 1
        }

        test("a contact that no longer permits the channel suppresses the message without sending anything") {
            val world = World(lookup = ContactLookup.Unavailable)
            world.produce(shipped(source())) shouldBe 1
            world.lookup = ContactLookup.Found(contact(anonymised = true))
            world.deliver.deliverDue(10) shouldBe 1

            val suppressed = world.only()
            suppressed.status shouldBe DeliveryStatus.SUPPRESSED
            suppressed.recipient.shouldBeNull()
            suppressed.content.body shouldBe ""
            suppressed.attempts shouldBe 0
            world.email.sent.shouldBeEmpty()
            world.notifications.attempts.shouldBeEmpty()
            world.outcomes.sent.shouldBeEmpty()
            world.outcomes.failed.shouldBeEmpty()
            world.deliver.deliverDue(10) shouldBe 0
        }

        test("an account identity no longer knows fails at once as an invalid recipient") {
            val world = World(lookup = ContactLookup.Unavailable)
            world.produce(shipped(source())) shouldBe 1
            world.lookup = ContactLookup.Unknown
            world.deliver.deliverDue(10) shouldBe 1

            val failed = world.only()
            failed.status shouldBe DeliveryStatus.FAILED
            failed.attempts shouldBe 1
            failed.lastFailure shouldBe DeliveryFailure(FailureCategory.INVALID_RECIPIENT, UNKNOWN_ACCOUNT)
            world.outcomes.failed shouldBe listOf(failed)
            world.email.sent.shouldBeEmpty()
        }

        test("a deferred order message goes by email only, even to a shopper opted in to SMS") {
            val world = World(lookup = ContactLookup.Unavailable)
            world.produce(shipped(source())) shouldBe 1
            world.lookup = ContactLookup.Found(contact(sms = true))
            world.deliver.deliverDue(10) shouldBe 1
            world.only().channel shouldBe NotificationChannel.EMAIL
            world.only().status shouldBe DeliveryStatus.SENT
            world.text.sent.shouldBeEmpty()
        }

        test("a message suppressed while its recipient was being looked up stays suppressed") {
            val world = World(lookup = ContactLookup.Unavailable)
            world.produce(shipped(source())) shouldBe 1
            val id = world.only().id
            world.lookup = ContactLookup.Found(contact())
            val suppressing =
                RecipientLookupPort { account, correlation ->
                    val current = world.notifications.stored.getValue(id)
                    world.notifications.stored[id] = current.suppress().getOrNull() ?: error("not suppressed")
                    world.contacts.lookup(account, correlation)
                }
            val deliver =
                AttemptDelivery(
                    world.notifications,
                    AttemptDelivery.Channels(world.email, world.text),
                    suppressing,
                    world.outcomes,
                    world.transactions,
                    world.clock,
                )
            deliver.deliverDue(10) shouldBe 1
            world.only().status shouldBe DeliveryStatus.SUPPRESSED
            world.only().awaitingRecipient shouldBe true
            world.notifications.attempts.shouldBeEmpty()
            world.outcomes.sent.shouldBeEmpty()
        }
    })

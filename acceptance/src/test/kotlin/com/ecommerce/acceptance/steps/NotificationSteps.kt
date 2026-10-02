package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.Accounts
import com.ecommerce.acceptance.support.Budgets
import com.ecommerce.acceptance.support.Environment
import com.ecommerce.acceptance.support.MailMessage
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.asAmount
import com.ecommerce.acceptance.support.eventually
import com.ecommerce.acceptance.support.items
import com.ecommerce.acceptance.support.remainsTrue
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

/**
 * Notifications (user story 6, notification.yaml and identity.yaml preferences). Emails are read from Mailpit;
 * SMS is simulated inside the notification service, so SMS deliveries are asserted on the notification history.
 */
class NotificationSteps(
    private val world: ScenarioWorld,
) {
    private var email: MailMessage? = null
    private var failedNotificationId: String? = null
    private var channelRefused = false

    @Then("the shopper receives an order confirmation email within {int} seconds")
    fun receivesTheConfirmation(seconds: Int) {
        val orderId = world.orderId()
        val within = Duration.ofSeconds(seconds.toLong())
        email =
            world.mailpit.awaitMessageTo(world.theShopper().email, within = within) {
                it.text.contains(orderId) || it.subject.contains(orderId)
            }
    }

    @Then("the email states the order number, the {string} line, the total of {money} and the delivery address")
    fun theEmailStatesTheDetails(
        alias: String,
        total: Long,
    ) {
        val text = checkNotNull(email) { "No confirmation email was received" }.text
        withClue("confirmation email body") {
            text shouldContain world.orderId()
            text shouldContain world.product(alias).name
            listOf(total.asAmount(), total.asAmount().replace('.', ',')).any { text.contains(it) } shouldBe true
            text shouldContain Accounts.ADDRESS_LINE
        }
    }

    @Given("the shopper has verified a phone number and opted in to SMS")
    fun verifiedPhoneAndSmsOptIn() {
        val shopper = world.theShopper()
        val phone = "+55119" + (1..PHONE_DIGITS).map { ('0'..'9').random() }.joinToString("")
        val requested = world.api.post(Paths.PHONE_VERIFICATIONS, mapOf("phoneNumber" to phone), shopper.bearer)
        requested shouldHaveStatus Status.ACCEPTED
        val code = world.mailpit.awaitSmsCode(phone)
        val confirmed = world.api.post(Paths.PHONE_CONFIRMATION, mapOf("code" to code), shopper.bearer)
        confirmed shouldHaveStatus Status.NO_CONTENT
        choosePreferences(listOf("email", "sms"))
    }

    @When("the shopper changes their notification preferences to email only")
    fun emailOnly() {
        choosePreferences(listOf("email"))
    }

    @Then("an email and an SMS are produced for the shipment of the order")
    fun emailAndSmsForTheShipment() {
        eventually(within = Budgets.notification) { channelsOf(world, SHIPPED) shouldContainAll listOf("email", "sms") }
    }

    @Then("only an email is produced for the shipment of the order")
    fun onlyEmailForTheShipment() {
        eventually(within = Budgets.notification) { channelsOf(world, SHIPPED) shouldContain "email" }
        remainsTrue { "sms" !in channelsOf(world, SHIPPED) }
    }

    @Given("the email channel refuses every message")
    fun theEmailChannelRefuses() {
        world.mailpit.refuseEveryMessage()
        channelRefused = true
    }

    @Then("the order confirmation email is retried with increasing delay")
    fun retriedWithIncreasingDelay() {
        val attempts = TreeMap<Int, Instant>()
        Awaitility
            .await("three delivery attempts of the order confirmation")
            .atMost(Environment.deliveryFailureBudget)
            .pollInterval(Duration.ofSeconds(1))
            .until {
                confirmationEmail()?.let { record(attempts, it) }
                (1..OBSERVED_ATTEMPTS).all { it in attempts }
            }
        val first = Duration.between(attempts.getValue(1), attempts.getValue(2))
        val second = Duration.between(attempts.getValue(2), attempts.getValue(OBSERVED_ATTEMPTS))
        withClue("delay before the third attempt ($second) versus before the second ($first)") {
            second.compareTo(first) shouldBeGreaterThan 0
        }
    }

    @Then("it is eventually recorded as failed and visible to operators")
    fun recordedAsFailed() {
        val query =
            Paths.query(
                Paths.FAILED_NOTIFICATIONS,
                "accountId" to checkNotNull(world.theShopper().accountId),
                "type" to CONFIRMATION,
            )
        eventually(within = Environment.deliveryFailureBudget, every = Duration.ofSeconds(FAILED_POLL_SECONDS)) {
            val failed = world.api.get(query, world.operator())
            failed shouldHaveStatus Status.OK
            val notification = failed.body.items().firstOrNull { it.string("orderId") == world.orderId() }
            withClue("failed notifications: ${failed.describe()}") { notification.shouldNotBeNull() }
            failedNotificationId = notification?.requireString("id")
        }
    }

    @When("the email channel recovers")
    fun theEmailChannelRecovers() {
        world.mailpit.acceptEveryMessage()
        channelRefused = false
    }

    @When("an operator retries the failed notification")
    fun anOperatorRetries() {
        val id = checkNotNull(failedNotificationId) { "No failed notification to retry" }
        world.api.post(Paths.notificationRetry(id), null, world.operator()) shouldHaveStatus Status.ACCEPTED
    }

    @Then("exactly one order confirmation email is sent for the order")
    fun exactlyOneConfirmation() {
        receivesTheConfirmation(Budgets.notification.seconds.toInt())
        val address = world.theShopper().email
        remainsTrue { world.mailpit.messagesMentioning(address, world.orderId()).size == 1 }
        notificationsOf(world, CONFIRMATION).count { it.string("channel") == "email" } shouldBe 1
    }

    @After("@chaos")
    fun restoreTheEmailChannel() {
        if (channelRefused) world.mailpit.acceptEveryMessage()
    }

    private fun choosePreferences(channels: List<String>) {
        val response = world.api.put(Paths.PREFERENCES, mapOf("channels" to channels), world.theShopper().bearer)
        response shouldHaveStatus Status.OK
    }

    private fun confirmationEmail(): JsonNode? =
        notificationsOf(world, CONFIRMATION).firstOrNull { it.string("channel") == "email" }

    private fun record(
        attempts: TreeMap<Int, Instant>,
        notification: JsonNode,
    ) {
        val count = notification.path("attempts").asInt()
        val at = notification.string("lastAttemptAt")
        if (count > 0 && at != null) attempts.putIfAbsent(count, Instant.parse(at))
    }

    companion object {
        private const val CONFIRMATION = "order_confirmation"
        private const val SHIPPED = "order_shipped"
        private const val PHONE_DIGITS = 8
        private const val OBSERVED_ATTEMPTS = 3
        private const val FAILED_POLL_SECONDS = 5L

        /** The shopper's notifications of [type] for the scenario's order (`listOwnNotifications`). */
        fun notificationsOf(
            world: ScenarioWorld,
            type: String,
        ): List<JsonNode> {
            val response = world.api.get(Paths.query(Paths.NOTIFICATIONS, "type" to type), world.theShopper().bearer)
            response shouldHaveStatus Status.OK
            return response.body.items().filter { it.string("orderId") == world.orderId() }
        }

        /** Channels on which a notification of [type] exists for the scenario's order. */
        fun channelsOf(
            world: ScenarioWorld,
            type: String,
        ): List<String> = notificationsOf(world, type).mapNotNull { it.string("channel") }
    }
}

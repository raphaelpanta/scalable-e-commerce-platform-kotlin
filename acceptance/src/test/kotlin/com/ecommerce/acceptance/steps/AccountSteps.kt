package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.Accounts
import com.ecommerce.acceptance.support.Cards
import com.ecommerce.acceptance.support.Checkout
import com.ecommerce.acceptance.support.Headers
import com.ecommerce.acceptance.support.Paths
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Shopper
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.items
import com.ecommerce.acceptance.support.remainsTrue
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import com.ecommerce.acceptance.support.untilNotThrottled
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.util.UUID

/** Registration, sessions, password reset and addresses (user story 3, identity.yaml). */
class AccountSteps(
    private val world: ScenarioWorld,
) {
    private var visitor: Shopper? = null
    private val addresses = mutableMapOf<String, String>()
    private var resetToken: String = ""
    private var oldPassword: String = ""
    private var known: Set<String> = emptySet()

    @When("a visitor registers with a new email address and a valid password")
    fun aVisitorRegisters() {
        val newcomer = Shopper.fresh()
        visitor = newcomer
        world.accounts.register(newcomer.email, newcomer.password)
    }

    @Then("the registration is acknowledged")
    fun theRegistrationIsAcknowledged() {
        val acknowledgement = world.api.last
        acknowledgement shouldHaveStatus Status.ACCEPTED
        acknowledgement.body.requireString("message").shouldNotBeBlank()
    }

    @Then("a verification message arrives for that address")
    fun aVerificationMessageArrives() {
        world.mailpit.awaitToken(theVisitor().email).shouldNotBeBlank()
    }

    @Then("signing in before verifying the email is refused")
    fun signingInBeforeVerifyingIsRefused() {
        val visitor = theVisitor()
        untilNotThrottled { world.accounts.signIn(visitor.email, visitor.password) } shouldHaveStatus Status.FORBIDDEN
    }

    @When("a visitor registers again with the shopper's email address")
    fun registersAgain() {
        world.accounts.register(world.theShopper().email, Shopper.newPassword())
    }

    @Then("the registration is acknowledged with the same generic message")
    fun acknowledgedWithTheSameMessage() {
        theRegistrationIsAcknowledged()
        val repeated = world.api.last
        val message = repeated.body.string("message")
        message shouldBe world.theShopper().registrationMessage
    }

    @Then("no second verification message is sent")
    fun noSecondVerificationMessage() {
        val email = world.theShopper().email
        remainsTrue { world.mailpit.messagesTo(email).size == 1 }
    }

    @Then("the shopper can see their own profile")
    fun canSeeTheirProfile() {
        val profile = world.api.get(Paths.ME, world.theShopper().bearer)
        profile shouldHaveStatus Status.OK
        profile.body.string("email") shouldBe world.theShopper().email
        profile.body.path("emailVerified").asBoolean() shouldBe true
    }

    @When("the shopper signs in with a wrong password {int} times in a row")
    fun wrongPasswordTimes(times: Int) {
        val shopper = world.theShopper()
        repeat(times) { attempt ->
            withClue("wrong password attempt ${attempt + 1}") {
                val refused = untilNotThrottled { world.accounts.signIn(shopper.email, "Wrong-${UUID.randomUUID()}") }
                refused shouldHaveStatus Status.UNAUTHORIZED
            }
        }
    }

    @Then("the next sign-in attempt is throttled even with the correct password")
    fun theNextAttemptIsThrottled() {
        val shopper = world.theShopper()
        val response = world.accounts.signIn(shopper.email, shopper.password)
        response shouldHaveStatus Status.TOO_MANY_REQUESTS
        response.header(Headers.RETRY_AFTER).shouldNotBeNull()
    }

    @When("the shopper adds a delivery address in {string}")
    fun addsAnAddress(city: String) {
        val response = world.accounts.addAddress(world.theShopper(), city)
        response shouldHaveStatus Status.CREATED
        addresses[city] = response.body.requireString("id")
    }

    @When("the shopper changes the {string} address to {string}")
    fun changesTheAddress(
        city: String,
        newCity: String,
    ) {
        val id = checkNotNull(addresses.remove(city)) { "No address in $city" }
        val changed = world.api.put(Paths.address(id), Accounts.address(newCity), world.theShopper().bearer)
        changed shouldHaveStatus Status.OK
        addresses[newCity] = id
    }

    @When("the shopper removes the {string} address")
    fun removesTheAddress(city: String) {
        val id = checkNotNull(addresses.remove(city)) { "No address in $city" }
        world.api.delete(Paths.address(id), world.theShopper().bearer) shouldHaveStatus Status.NO_CONTENT
    }

    @When("the shopper signs out and signs in again")
    fun signsOutAndIn() {
        val shopper = world.theShopper()
        world.api.delete(Paths.CURRENT_SESSION, shopper.bearer) shouldHaveStatus Status.NO_CONTENT
        shopper.accessToken = null
        world.accounts.signInSuccessfully(shopper)
    }

    @Then("the shopper's delivery addresses are exactly {string}")
    fun addressesAreExactly(city: String) {
        val response = world.api.get(Paths.ADDRESSES, world.theShopper().bearer)
        response shouldHaveStatus Status.OK
        response.body.items().map { it.string("city") } shouldContainExactly listOf(city)
    }

    @When("the shopper requests a password reset")
    fun requestsAReset() {
        val shopper = world.theShopper()
        known = world.mailpit.idsTo(shopper.email)
        world.api.post(Paths.PASSWORD_RESETS, mapOf("email" to shopper.email)) shouldHaveStatus Status.ACCEPTED
    }

    @Then("a reset message arrives for the shopper")
    fun aResetMessageArrives() {
        resetToken = world.mailpit.awaitToken(world.theShopper().email, known)
        resetToken.shouldNotBeBlank()
    }

    @When("the shopper sets a new password with the reset")
    fun setsANewPassword() {
        val shopper = world.theShopper()
        oldPassword = shopper.password
        shopper.password = Shopper.newPassword()
        completeReset(shopper.password) shouldHaveStatus Status.NO_CONTENT
    }

    @Then("signing in with the old password is refused")
    fun oldPasswordRefused() {
        val email = world.theShopper().email
        untilNotThrottled { world.accounts.signIn(email, oldPassword) } shouldHaveStatus Status.UNAUTHORIZED
    }

    @Then("signing in with the new password succeeds")
    fun newPasswordAccepted() {
        val shopper = world.theShopper()
        untilNotThrottled { world.accounts.signIn(shopper.email, shopper.password) } shouldHaveStatus Status.OK
    }

    @Then("the same reset cannot be used a second time")
    fun resetIsSingleUse() {
        completeReset(Shopper.newPassword()).status shouldBeIn listOf(Status.BAD_REQUEST, Status.UNPROCESSABLE)
    }

    @When("the anonymous shopper tries to place the order")
    fun anonymousCheckout() {
        val checkout = Checkout(null, Checkout.body(UUID.randomUUID().toString(), "rev-anonymous", Cards.APPROVED))
        world.orders.place(checkout)
    }

    private fun completeReset(newPassword: String) =
        world.api.post(Paths.PASSWORD_RESET_COMPLETION, mapOf("token" to resetToken, "newPassword" to newPassword))

    private fun theVisitor(): Shopper = checkNotNull(visitor) { "No visitor registered in this scenario" }
}

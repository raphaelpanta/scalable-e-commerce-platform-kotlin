package com.ecommerce.payment.infrastructure

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.infrastructure.PactFixtures.ADA_CONTACT
import com.ecommerce.payment.infrastructure.PactFixtures.CORRELATION_ID
import com.ecommerce.payment.infrastructure.messaging.PaymentEnvelopes
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.testing.JwtFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val APPROVED_AT = "2026-10-02T10:15:01Z"

/** The account a storefront provider state signs in: the subject and role of the bearer the verifier sends. */
private data class StorefrontCaller(
    val accountId: UUID,
    val role: String,
) {
    companion object {
        /** ana@example.com, the shopper of the storefront interactions. */
        val ANA = StorefrontCaller(UUID.fromString(PactFixtures.ADA), "shopper")
    }
}

/**
 * Provider side of every pact whose provider is `payment`: the platform probe's health check, the four charge calls of
 * the order service (pact-interactions.md section 2.4) replayed against the running service over HTTP with
 * parameterised provider states, the storefront's reads of payment.yaml (feature 005 pact-matrix.md P2, the
 * placeholder bearer replaced by a test token of the signed-in caller, [BearerRewritingTarget]), and the payment events
 * consumed by order and notification (section 3.2), each produced from the fixture payments by the outbox's own
 * mapping. Shared by [PaymentProviderVerificationTest] (pacts of `build/pacts`) and [PaymentBrokerVerificationTest]
 * (pacts of the Pact Broker), which only choose the pact source; both are tagged `provider` and run in
 * `contractVerify`.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
// Abstract: JUnit runs only the subclasses, which choose the pact source (folder or broker); one method per provider
// state and per message row.
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
abstract class PaymentProviderStates {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    lateinit var harness: PaymentHarness

    private var caller: StorefrontCaller = StorefrontCaller.ANA

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        caller = StorefrontCaller.ANA
        context?.let {
            it.target =
                if (it.interaction.isAsynchronousMessage()) {
                    MessageTestTarget(listOf(PaymentProviderStates::class.java.packageName))
                } else {
                    BearerRewritingTarget(port) { "Bearer " + jwt.tokenFor(caller.accountId, listOf(caller.role)) }
                }
        }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun serviceHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("the payment service is running")
    fun serviceRunning() {
        // The Spring context and its database are already up; nothing to arrange.
    }

    @State("no payment exists for the order")
    fun noPayment(parameters: Map<String, Any>) {
        harness.reset()
        check(parameters.containsKey("orderId")) { "the state names the order" }
    }

    @State("a charge attempt exists")
    fun chargeAttemptExists(parameters: Map<String, Any>) {
        harness.reset()
        harness.store(
            PactFixtures.attempt(
                id = parameters.text("attemptId"),
                orderId = parameters.text("orderId"),
                amountMinor = parameters.amount(),
                key = parameters.text("idempotencyKey"),
                outcome = checkNotNull(PaymentOutcome.fromWire(parameters.text("outcome"))),
                reference = parameters["providerReference"]?.toString(),
                createdAt = parameters.text("createdAt"),
                accountId = parameters.text("accountId"),
                paymentMethodRef = parameters.text("paymentMethodRef"),
            ),
        )
    }

    // The storefront states of pact-matrix.md "Storefront to payment", named verbatim with the full order id the
    // storefront sends; the caller is ana@example.com (a shopper) unless the state signs in the operator.

    @State("order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 has a declined payment attempt")
    fun order1HasDeclinedAttempt() {
        harness.reset()
        harness.store(PactFixtures.declinedChargeOfOrder1)
    }

    @State("order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 has no payment attempts")
    fun order1HasNoAttempts() {
        harness.reset()
    }

    @State("a shopper ana@example.com is signed in")
    fun shopperSignedIn() {
        caller = StorefrontCaller.ANA
    }

    // The message states name the fixture payments of pact-interactions.md; the events are built from those
    // fixtures, so the states arrange nothing.

    @State("a payment was approved")
    fun paymentApprovedState() = Unit

    @State("a payment was declined")
    fun paymentDeclinedState() = Unit

    @State("a payment is pending")
    fun paymentPendingState() = Unit

    @State("a refund was recorded")
    fun refundRecordedState() = Unit

    @PactVerifyProvider("a PaymentApproved event for an order awaiting payment")
    fun paymentApproved(): MessageAndMetadata = message(PaymentEvent.ChargeRecorded(PactFixtures.approvedCharge))

    @PactVerifyProvider("a PaymentDeclined event for an order awaiting payment")
    fun paymentDeclined(): MessageAndMetadata = message(PaymentEvent.ChargeRecorded(PactFixtures.declinedCharge))

    @PactVerifyProvider("a PaymentPending event for an order awaiting payment")
    fun paymentPending(): MessageAndMetadata = message(PaymentEvent.ChargeRecorded(PactFixtures.pendingCharge))

    @PactVerifyProvider("a RefundRecorded event for a cancelled order")
    fun refundRecordedForOrder(): MessageAndMetadata =
        message(PaymentEvent.RefundRecorded(PactFixtures.refund, ADA_CONTACT))

    @PactVerifyProvider("a RefundRecorded event for the refund confirmation")
    fun refundRecordedForNotification(): MessageAndMetadata =
        message(PaymentEvent.RefundRecorded(PactFixtures.refund, ADA_CONTACT))

    private fun message(event: PaymentEvent): MessageAndMetadata {
        val envelope = PaymentEnvelopes.envelopeOf(event, CORRELATION_ID, ENVELOPES)
        return MessageAndMetadata(
            EnvelopeJson.write(envelope).toByteArray(),
            mapOf("topic" to Topic.PAYMENT, "kafkaKey" to envelope.aggregateId.toString()),
        )
    }

    companion object {
        private val ENVELOPES = EnvelopeFactory("payment", Clock.fixed(Instant.parse(APPROVED_AT), ZoneOffset.UTC))

        /** Signs the bearers of the replayed storefront requests; serves the JWKS the service validates them with. */
        private val jwt: JwtFixture = JwtFixture().also { it.startJwks() }

        @JvmStatic
        @DynamicPropertySource
        fun security(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
        }

        private fun Map<String, Any>.text(name: String): String =
            checkNotNull(this[name]) { "state parameter $name" }.toString()

        private fun Map<String, Any>.amount(): Long = (checkNotNull(this["amountMinor"]) as Number).toLong()
    }
}

package com.ecommerce.payment.infrastructure

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.infrastructure.PactFixtures.ADA_CONTACT
import com.ecommerce.payment.infrastructure.PactFixtures.CORRELATION_ID
import com.ecommerce.payment.infrastructure.messaging.PaymentEnvelopes
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.Topic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val APPROVED_AT = "2026-10-02T10:15:01Z"

/**
 * Provider side of every pact whose provider is `payment` (repository root `build/pacts`): the platform probe's health
 * check, the four charge calls of the order service (pact-interactions.md section 2.4) replayed against
 * the running service over HTTP with parameterised provider states, and the payment events consumed by order and
 * notification (section 3.2), each produced from the fixture payments by the outbox's own mapping. Tagged
 * `provider`, so it runs in `contractVerify`; with no pact for `payment` the verification is skipped, not failed.
 */
@Tag("provider")
@Provider("payment")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
@Suppress("TooManyFunctions") // one method per provider state and per message row
class PaymentProviderVerificationTest(
    @LocalServerPort private val port: Int,
) {
    @Autowired
    lateinit var harness: PaymentHarness

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.let {
            it.target =
                if (it.interaction.isAsynchronousMessage()) {
                    MessageTestTarget(listOf(PaymentProviderVerificationTest::class.java.packageName))
                } else {
                    HttpTestTarget("localhost", port)
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

    private companion object {
        val ENVELOPES = EnvelopeFactory("payment", Clock.fixed(Instant.parse(APPROVED_AT), ZoneOffset.UTC))

        fun Map<String, Any>.text(name: String): String =
            checkNotNull(this[name]) { "state parameter $name" }.toString()

        fun Map<String, Any>.amount(): Long = (checkNotNull(this["amountMinor"]) as Number).toLong()
    }
}

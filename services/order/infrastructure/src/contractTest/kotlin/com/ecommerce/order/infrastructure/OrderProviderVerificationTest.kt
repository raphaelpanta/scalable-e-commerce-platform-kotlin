package com.ecommerce.order.infrastructure

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
import com.ecommerce.order.domain.OrderId
import com.ecommerce.platform.messaging.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * Provider side of every pact whose provider is `order` (repository root `build/pacts`): the health check of the
 * platform probe over HTTP, and the order events consumed by notification, catalog and payment
 * (pact-interactions.md section 3.2), each produced from the fixture orders by the outbox's own mapping. Tagged
 * `provider`, so it runs in `contractVerify`; with no pact for `order` the verification is skipped, not failed.
 */
@Tag("provider")
@Provider("order")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class)
@Suppress("TooManyFunctions") // one producer method per message row and one method per provider state
class OrderProviderVerificationTest(
    @LocalServerPort private val port: Int,
) {
    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.let {
            it.target =
                if (it.interaction.isAsynchronousMessage()) {
                    MessageTestTarget(listOf(OrderProviderVerificationTest::class.java.packageName))
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

    @State("the order service is running")
    fun serviceRunning() {
        // The Spring context and its database are already up; nothing to arrange.
    }

    // The message states name the fixture orders of pact-interactions.md; the events are built from those
    // fixtures, so the states arrange nothing.

    @State("an order was placed")
    fun orderPlacedState() = Unit

    @State("an order was paid")
    fun orderPaidState() = Unit

    @State("an order payment failed")
    fun paymentFailedState() = Unit

    @State("an order was shipped")
    fun orderShippedState() = Unit

    @State("an order was delivered")
    fun orderDeliveredState() = Unit

    @State("an order was cancelled")
    fun orderCancelledState() = Unit

    @PactVerifyProvider("an OrderPlaced event that triggers the charge")
    fun orderPlacedForPayment(): MessageAndMetadata = message(OrderEventExamples.orderPlaced(), PAID)

    @PactVerifyProvider("an OrderPaid event for the order confirmation")
    fun orderPaidForNotification(): MessageAndMetadata = message(OrderEventExamples.orderPaid(), PAID)

    @PactVerifyProvider("an OrderPaid event that commits the reservation")
    fun orderPaidForCatalog(): MessageAndMetadata = message(OrderEventExamples.orderPaid(), PAID)

    @PactVerifyProvider("an OrderPaymentFailed event for the payment failure message")
    fun paymentFailedForNotification(): MessageAndMetadata = message(OrderEventExamples.orderPaymentFailed(), DECLINED)

    @PactVerifyProvider("an OrderPaymentFailed event that releases the reservation")
    fun paymentFailedForCatalog(): MessageAndMetadata = message(OrderEventExamples.orderPaymentFailed(), DECLINED)

    @PactVerifyProvider("an OrderShipped event for the shipping message")
    fun orderShipped(): MessageAndMetadata = message(OrderEventExamples.orderShipped(), PAID)

    @PactVerifyProvider("an OrderDelivered event for the delivery message")
    fun orderDelivered(): MessageAndMetadata = message(OrderEventExamples.orderDelivered(), PAID)

    @PactVerifyProvider("an OrderCancelled event for the cancellation message")
    fun cancelledForNotification(): MessageAndMetadata = message(OrderEventExamples.orderCancelledByShopper(), PAID)

    @PactVerifyProvider("an OrderCancelled event that triggers the refund")
    fun cancelledForPayment(): MessageAndMetadata = message(OrderEventExamples.orderCancelledByShopper(), PAID)

    @PactVerifyProvider("an OrderCancelled event after the payment expired")
    fun expiredForCatalog(): MessageAndMetadata = message(OrderEventExamples.orderCancelledAfterExpiry(), PENDING)

    private fun message(
        json: String,
        orderId: OrderId,
    ): MessageAndMetadata = MessageAndMetadata(json.toByteArray(), OrderEventExamples.metadata(orderId))

    private companion object {
        val PAID = OrderEventExamples.PAID_ORDER
        val PENDING = OrderEventExamples.PENDING_ORDER
        val DECLINED = OrderEventExamples.DECLINED_ORDER
    }
}

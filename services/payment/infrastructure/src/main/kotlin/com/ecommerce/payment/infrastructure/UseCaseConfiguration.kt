package com.ecommerce.payment.infrastructure

import com.ecommerce.payment.application.AuthoriseCharge
import com.ecommerce.payment.application.ChargePlacedOrder
import com.ecommerce.payment.application.GetPaymentAttempt
import com.ecommerce.payment.application.GetRefund
import com.ecommerce.payment.application.GetSimulatorRules
import com.ecommerce.payment.application.ListPaymentAttemptsForOrder
import com.ecommerce.payment.application.ListRefundsForOrder
import com.ecommerce.payment.application.PaymentIds
import com.ecommerce.payment.application.PaymentLedger
import com.ecommerce.payment.application.RecordRefund
import com.ecommerce.payment.application.RefundCancelledOrder
import com.ecommerce.payment.domain.PaymentProviderPort
import com.ecommerce.payment.domain.SimulatedPaymentRules
import com.ecommerce.payment.domain.SimulatorRules
import com.ecommerce.payment.infrastructure.messaging.OutboxPaymentEventPublisher
import com.ecommerce.payment.infrastructure.messaging.PaymentEventHandlers
import com.ecommerce.payment.infrastructure.messaging.PaymentEventListeners
import com.ecommerce.payment.infrastructure.persistence.R2dbcPaymentAttemptRepository
import com.ecommerce.payment.infrastructure.persistence.R2dbcRefundRepository
import com.ecommerce.payment.infrastructure.persistence.R2dbcTransactions
import com.ecommerce.payment.infrastructure.persistence.RandomPaymentIds
import com.ecommerce.payment.infrastructure.provider.SimulatedPaymentProvider
import com.ecommerce.payment.infrastructure.web.InternalPaymentHandlers
import com.ecommerce.payment.infrastructure.web.PaymentQueryHandlers
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock
import java.time.Duration

/** Wires the framework-free use cases to their adapters: R2DBC, the outbox, the simulated provider. */
@Configuration(proxyBeanMethods = false)
@Suppress("TooManyFunctions") // one factory per use case and adapter keeps the wiring explicit
class UseCaseConfiguration {
    /** UTC with microsecond ticks: the precision PostgreSQL keeps, so stored and returned instants are equal. */
    @Bean
    fun clock(): Clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(NANOS_PER_MICRO))

    /** The rule document of the simulated provider, also published by `GET /api/v1/payments/simulator/rules`. */
    @Bean
    fun simulatorRules(): SimulatorRules = SimulatedPaymentRules.DOCUMENT

    @Bean
    fun paymentProvider(rules: SimulatorRules): PaymentProviderPort = SimulatedPaymentProvider(rules)

    @Bean
    fun paymentIds(): PaymentIds = RandomPaymentIds()

    @Bean
    fun paymentLedger(
        database: DatabaseClient,
        transactionManager: ReactiveTransactionManager,
        outbox: OutboxPublisher,
        envelopes: EnvelopeFactory,
    ): PaymentLedger =
        PaymentLedger(
            R2dbcPaymentAttemptRepository(database),
            R2dbcRefundRepository(database),
            OutboxPaymentEventPublisher(outbox, envelopes),
            R2dbcTransactions(TransactionalOperator.create(transactionManager)),
        )

    @Bean
    fun authoriseCharge(
        ledger: PaymentLedger,
        provider: PaymentProviderPort,
        ids: PaymentIds,
        clock: Clock,
    ): AuthoriseCharge = AuthoriseCharge(ledger, provider, ids, clock)

    @Bean
    fun recordRefund(
        ledger: PaymentLedger,
        provider: PaymentProviderPort,
        ids: PaymentIds,
        clock: Clock,
    ): RecordRefund = RecordRefund(ledger, provider, ids, clock)

    @Bean
    fun internalPaymentHandlers(
        authoriseCharge: AuthoriseCharge,
        recordRefund: RecordRefund,
    ): InternalPaymentHandlers = InternalPaymentHandlers(authoriseCharge, recordRefund)

    @Bean
    fun paymentQueryHandlers(
        ledger: PaymentLedger,
        rules: SimulatorRules,
    ): PaymentQueryHandlers =
        PaymentQueryHandlers(
            GetPaymentAttempt(ledger.attempts),
            ListPaymentAttemptsForOrder(ledger.attempts),
            ListRefundsForOrder(ledger.attempts, ledger.refunds),
            GetRefund(ledger.refunds),
            GetSimulatorRules(rules),
        )

    @Bean
    fun paymentEventHandlers(
        ledger: PaymentLedger,
        authoriseCharge: AuthoriseCharge,
        recordRefund: RecordRefund,
        clock: Clock,
    ): PaymentEventHandlers =
        PaymentEventHandlers(ChargePlacedOrder(authoriseCharge), RefundCancelledOrder(ledger, recordRefund, clock))

    @Bean
    fun paymentEventListeners(
        events: EventListenerSupport,
        handlers: PaymentEventHandlers,
    ): PaymentEventListeners = PaymentEventListeners(events, handlers)

    private companion object {
        const val NANOS_PER_MICRO = 1000L
    }
}

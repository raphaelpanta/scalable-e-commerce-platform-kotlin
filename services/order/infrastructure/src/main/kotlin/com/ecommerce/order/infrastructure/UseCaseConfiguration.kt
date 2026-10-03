package com.ecommerce.order.infrastructure

import com.ecommerce.order.application.AnonymiseAccountOrders
import com.ecommerce.order.application.ApplyPaymentOutcome
import com.ecommerce.order.application.CancelOwnOrder
import com.ecommerce.order.application.CatalogPort
import com.ecommerce.order.application.CheckoutPorts
import com.ecommerce.order.application.ExpirePendingPayments
import com.ecommerce.order.application.GetOwnOrder
import com.ecommerce.order.application.IdempotencyStore
import com.ecommerce.order.application.ListOwnOrders
import com.ecommerce.order.application.OrderRepository
import com.ecommerce.order.application.OrderStore
import com.ecommerce.order.application.PlaceOrder
import com.ecommerce.order.application.PurgeExpiredIdempotencyRecords
import com.ecommerce.order.application.RecordRefund
import com.ecommerce.order.application.TransitionOrderStatus
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.infrastructure.clients.AddressClient
import com.ecommerce.order.infrastructure.clients.CartClient
import com.ecommerce.order.infrastructure.clients.PaymentClient
import com.ecommerce.order.infrastructure.clients.StockReservationClient
import com.ecommerce.order.infrastructure.jobs.IdempotencyPurgeJob
import com.ecommerce.order.infrastructure.messaging.OrderEventHandlers
import com.ecommerce.order.infrastructure.messaging.OrderEventListeners
import com.ecommerce.order.infrastructure.messaging.OutboxOrderEventPublisher
import com.ecommerce.order.infrastructure.persistence.R2dbcIdempotencyStore
import com.ecommerce.order.infrastructure.persistence.R2dbcOrderRepository
import com.ecommerce.order.infrastructure.persistence.R2dbcTransactions
import com.ecommerce.order.infrastructure.web.CheckoutResponseRenderer
import com.ecommerce.order.infrastructure.web.OrderCommandHandlers
import com.ecommerce.order.infrastructure.web.OrderQueryHandlers
import com.ecommerce.platform.http.WebClientDefaults
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.security.PlatformSecurityProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Wires the framework-free use cases to their adapters: R2DBC, the outbox, the internal HTTP clients. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderProperties::class)
@Suppress("TooManyFunctions") // one factory per use case and adapter keeps the wiring explicit
class UseCaseConfiguration {
    /** UTC with microsecond ticks: the precision PostgreSQL keeps, so stored and returned instants are equal. */
    @Bean
    fun clock(): Clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(NANOS_PER_MICRO))

    @Bean
    fun orderStore(
        database: DatabaseClient,
        transactionManager: ReactiveTransactionManager,
        outbox: OutboxPublisher,
        envelopes: EnvelopeFactory,
    ): OrderStore =
        OrderStore(
            R2dbcOrderRepository(database),
            OutboxOrderEventPublisher(outbox, envelopes),
            R2dbcTransactions(TransactionalOperator.create(transactionManager)),
        )

    @Bean
    fun orderRepository(store: OrderStore): OrderRepository = store.orders

    @Bean
    fun idempotencyStore(database: DatabaseClient): IdempotencyStore = R2dbcIdempotencyStore(database)

    @Bean
    fun catalogPort(clients: InternalClients): CatalogPort =
        StockReservationClient(clients.client(clients.urls.catalogUrl))

    @Bean
    fun paymentClient(clients: InternalClients): PaymentClient = PaymentClient(clients.client(clients.urls.paymentUrl))

    @Bean
    fun checkoutPorts(
        clients: InternalClients,
        catalog: CatalogPort,
        payment: PaymentClient,
    ): CheckoutPorts =
        CheckoutPorts(
            cart = CartClient(clients.client(clients.urls.cartUrl)),
            catalog = catalog,
            payment = payment,
            accounts = AddressClient(clients.client(clients.urls.identityUrl)),
        )

    @Bean
    fun internalClients(
        builder: WebClient.Builder,
        properties: OrderProperties,
        security: PlatformSecurityProperties,
    ): InternalClients = InternalClients(builder, properties.clients, security.internalToken)

    @Bean
    fun checkoutResponseRenderer(json: JsonMapper): CheckoutResponseRenderer = CheckoutResponseRenderer(json)

    @Bean
    fun placeOrder(
        ports: CheckoutPorts,
        store: OrderStore,
        idempotency: IdempotencyStore,
        responses: CheckoutResponseRenderer,
        clock: Clock,
    ): PlaceOrder = PlaceOrder(ports, store, idempotency, responses, { OrderId(UUID.randomUUID()) }, clock)

    @Bean
    fun orderCommandHandlers(
        placeOrder: PlaceOrder,
        store: OrderStore,
        catalog: CatalogPort,
        responses: CheckoutResponseRenderer,
        clock: Clock,
    ): OrderCommandHandlers =
        OrderCommandHandlers(
            placeOrder,
            CancelOwnOrder(store, catalog, clock),
            TransitionOrderStatus(store, catalog, clock),
            responses,
        )

    @Bean
    fun orderQueryHandlers(orders: OrderRepository): OrderQueryHandlers =
        OrderQueryHandlers(ListOwnOrders(orders), GetOwnOrder(orders))

    @Bean
    fun expirePendingPayments(
        store: OrderStore,
        catalog: CatalogPort,
        clock: Clock,
    ): ExpirePendingPayments = ExpirePendingPayments(store, catalog, clock)

    @Bean
    fun idempotencyPurgeJob(
        idempotency: IdempotencyStore,
        properties: OrderProperties,
        clock: Clock,
    ): IdempotencyPurgeJob =
        IdempotencyPurgeJob(PurgeExpiredIdempotencyRecords(idempotency, clock), properties.idempotencyPurge.interval)

    @Bean
    fun orderEventHandlers(
        store: OrderStore,
        clock: Clock,
    ): OrderEventHandlers =
        OrderEventHandlers(ApplyPaymentOutcome(store, clock), RecordRefund(store, clock), AnonymiseAccountOrders(store))

    @Bean
    fun orderEventListeners(
        events: EventListenerSupport,
        handlers: OrderEventHandlers,
    ): OrderEventListeners = OrderEventListeners(events, handlers)

    private companion object {
        const val NANOS_PER_MICRO = 1000L
    }
}

/** Builds the internal WebClients (platform timeouts, retries on connection errors, token and correlation id). */
class InternalClients(
    private val builder: WebClient.Builder,
    val urls: OrderProperties.Clients,
    private val internalToken: String,
) {
    fun client(baseUrl: String): WebClient = WebClientDefaults.internalClient(builder, baseUrl, internalToken)
}

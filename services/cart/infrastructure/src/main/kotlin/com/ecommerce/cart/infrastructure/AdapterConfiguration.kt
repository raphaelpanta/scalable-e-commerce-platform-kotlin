package com.ecommerce.cart.infrastructure

import com.ecommerce.cart.application.AddLine
import com.ecommerce.cart.application.AnonymousTokens
import com.ecommerce.cart.application.CartEvents
import com.ecommerce.cart.application.CartRepository
import com.ecommerce.cart.application.CatalogPricing
import com.ecommerce.cart.application.ClearAccountCart
import com.ecommerce.cart.application.ClearCart
import com.ecommerce.cart.application.DeleteAccountCart
import com.ecommerce.cart.application.GetAccountCart
import com.ecommerce.cart.application.GetCart
import com.ecommerce.cart.application.IssuedToken
import com.ecommerce.cart.application.MergeCarts
import com.ecommerce.cart.application.PurgeIdleCarts
import com.ecommerce.cart.application.RemoveLine
import com.ecommerce.cart.application.RemoveOrderedLines
import com.ecommerce.cart.application.Transactions
import com.ecommerce.cart.application.UpdateLineQuantity
import com.ecommerce.cart.infrastructure.catalog.CatalogPricingAdapter
import com.ecommerce.cart.infrastructure.jobs.CartPurgeJob
import com.ecommerce.cart.infrastructure.messaging.CartEventListeners
import com.ecommerce.cart.infrastructure.messaging.OutboxCartEvents
import com.ecommerce.cart.infrastructure.persistence.R2dbcCartRepository
import com.ecommerce.cart.infrastructure.persistence.ReactiveTransactions
import com.ecommerce.cart.infrastructure.web.CartHandlers
import com.ecommerce.cart.infrastructure.web.InternalCartHandlers
import com.ecommerce.platform.core.values.SecretToken
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.security.PlatformSecurityProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.reactive.function.client.WebClient

/** The adapters around the use cases: persistence, catalogue client, outbox, tokens, web handlers, consumers, jobs. */
@Configuration(proxyBeanMethods = false)
class AdapterConfiguration {
    @Bean
    fun cartTransactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)

    @Bean
    fun cartRepository(
        database: DatabaseClient,
        cartTransactionalOperator: TransactionalOperator,
    ): CartRepository = R2dbcCartRepository(database, cartTransactionalOperator)

    @Bean
    fun transactions(cartTransactionalOperator: TransactionalOperator): Transactions =
        ReactiveTransactions(cartTransactionalOperator)

    @Bean
    fun catalogPricing(
        builder: WebClient.Builder,
        properties: CartProperties,
        security: PlatformSecurityProperties,
    ): CatalogPricing = CatalogPricingAdapter.create(builder, properties.catalogUrl, security.internalToken)

    @Bean
    fun cartEvents(
        outbox: OutboxPublisher,
        envelopes: EnvelopeFactory,
    ): CartEvents = OutboxCartEvents(outbox, envelopes)

    /** Opaque anonymous cart tokens: 256 random bits, url-safe; only the SHA-256 hash is stored. */
    @Bean
    fun anonymousTokens(): AnonymousTokens =
        AnonymousTokens { SecretToken.generate().let { IssuedToken(it.value, it.hash()) } }

    @Bean
    @Suppress("LongParameterList") // one use case per operation of cart.yaml
    fun cartHandlers(
        getCart: GetCart,
        addLine: AddLine,
        updateLineQuantity: UpdateLineQuantity,
        removeLine: RemoveLine,
        clearCart: ClearCart,
        mergeCarts: MergeCarts,
    ): CartHandlers = CartHandlers(getCart, addLine, updateLineQuantity, removeLine, clearCart, mergeCarts)

    @Bean
    fun internalCartHandlers(
        getAccountCart: GetAccountCart,
        clearAccountCart: ClearAccountCart,
    ): InternalCartHandlers = InternalCartHandlers(getAccountCart, clearAccountCart)

    @Bean
    fun cartEventListeners(
        events: EventListenerSupport,
        removeOrderedLines: RemoveOrderedLines,
        deleteAccountCart: DeleteAccountCart,
    ): CartEventListeners = CartEventListeners(events, removeOrderedLines, deleteAccountCart)

    @Bean
    fun cartPurgeJob(
        purgeIdleCarts: PurgeIdleCarts,
        properties: CartProperties,
    ): CartPurgeJob = CartPurgeJob(purgeIdleCarts, properties.purgeInterval)
}

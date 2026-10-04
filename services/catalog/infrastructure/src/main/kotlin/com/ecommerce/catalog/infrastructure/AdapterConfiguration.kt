package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.application.AuditLog
import com.ecommerce.catalog.application.CategoryRepository
import com.ecommerce.catalog.application.CommitReservation
import com.ecommerce.catalog.application.ExpireReservations
import com.ecommerce.catalog.application.GetCategory
import com.ecommerce.catalog.application.GetPricing
import com.ecommerce.catalog.application.GetPricingBatch
import com.ecommerce.catalog.application.GetProduct
import com.ecommerce.catalog.application.InventoryRepository
import com.ecommerce.catalog.application.ListCategories
import com.ecommerce.catalog.application.ListProducts
import com.ecommerce.catalog.application.ProductRepository
import com.ecommerce.catalog.application.ReleaseReservation
import com.ecommerce.catalog.application.ReservationRepository
import com.ecommerce.catalog.application.ReserveStock
import com.ecommerce.catalog.application.SearchProducts
import com.ecommerce.catalog.application.SettleOrderReservation
import com.ecommerce.catalog.application.StockAdjustmentRepository
import com.ecommerce.catalog.application.StockEvents
import com.ecommerce.catalog.application.Transactions
import com.ecommerce.catalog.application.admin.AddProductImage
import com.ecommerce.catalog.application.admin.AdjustStock
import com.ecommerce.catalog.application.admin.AuthorizeOperator
import com.ecommerce.catalog.application.admin.CreateCategory
import com.ecommerce.catalog.application.admin.CreateProduct
import com.ecommerce.catalog.application.admin.ReinstateCategory
import com.ecommerce.catalog.application.admin.ReinstateProduct
import com.ecommerce.catalog.application.admin.UpdateCategory
import com.ecommerce.catalog.application.admin.UpdateProduct
import com.ecommerce.catalog.application.admin.WithdrawCategory
import com.ecommerce.catalog.application.admin.WithdrawProduct
import com.ecommerce.catalog.infrastructure.audit.R2dbcAuditLog
import com.ecommerce.catalog.infrastructure.jobs.ReservationExpiryJob
import com.ecommerce.catalog.infrastructure.messaging.CatalogEventListeners
import com.ecommerce.catalog.infrastructure.messaging.OutboxStockEvents
import com.ecommerce.catalog.infrastructure.persistence.R2dbcCategoryRepository
import com.ecommerce.catalog.infrastructure.persistence.R2dbcInventoryRepository
import com.ecommerce.catalog.infrastructure.persistence.R2dbcProductRepository
import com.ecommerce.catalog.infrastructure.persistence.R2dbcReservationRepository
import com.ecommerce.catalog.infrastructure.persistence.R2dbcStockAdjustmentRepository
import com.ecommerce.catalog.infrastructure.persistence.ReactiveTransactions
import com.ecommerce.catalog.infrastructure.web.CatalogAdminHandlers
import com.ecommerce.catalog.infrastructure.web.CatalogAdminUseCases
import com.ecommerce.catalog.infrastructure.web.CatalogQueryHandlers
import com.ecommerce.catalog.infrastructure.web.PricingHandlers
import com.ecommerce.catalog.infrastructure.web.ReservationHandlers
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/** The adapters around the use cases: persistence, outbox, audit, web handlers, the order consumer and the job. */
@Suppress("TooManyFunctions") // one bean per adapter
@Configuration(proxyBeanMethods = false)
class AdapterConfiguration {
    @Bean
    fun catalogTransactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)

    @Bean
    fun transactions(catalogTransactionalOperator: TransactionalOperator): Transactions =
        ReactiveTransactions(catalogTransactionalOperator)

    @Bean
    fun productRepository(
        database: DatabaseClient,
        catalogTransactionalOperator: TransactionalOperator,
    ): ProductRepository = R2dbcProductRepository(database, catalogTransactionalOperator)

    @Bean
    fun categoryRepository(database: DatabaseClient): CategoryRepository = R2dbcCategoryRepository(database)

    @Bean
    fun inventoryRepository(database: DatabaseClient): InventoryRepository = R2dbcInventoryRepository(database)

    @Bean
    fun reservationRepository(
        database: DatabaseClient,
        catalogTransactionalOperator: TransactionalOperator,
    ): ReservationRepository = R2dbcReservationRepository(database, catalogTransactionalOperator)

    @Bean
    fun stockAdjustmentRepository(database: DatabaseClient): StockAdjustmentRepository =
        R2dbcStockAdjustmentRepository(database)

    @Bean
    fun stockEvents(
        outbox: OutboxPublisher,
        envelopes: EnvelopeFactory,
    ): StockEvents = OutboxStockEvents(outbox, envelopes)

    @Bean
    fun auditLog(database: DatabaseClient): AuditLog = R2dbcAuditLog(database)

    @Bean
    @Suppress("LongParameterList") // one use case per read of catalog.yaml
    fun catalogQueryHandlers(
        listProducts: ListProducts,
        searchProducts: SearchProducts,
        getProduct: GetProduct,
        listCategories: ListCategories,
        getCategory: GetCategory,
    ): CatalogQueryHandlers =
        CatalogQueryHandlers(listProducts, searchProducts, getProduct, listCategories, getCategory)

    @Bean
    @Suppress("LongParameterList") // one use case per operator operation of catalog.yaml
    fun catalogAdminHandlers(
        authorizeOperator: AuthorizeOperator,
        createProduct: CreateProduct,
        updateProduct: UpdateProduct,
        withdrawProduct: WithdrawProduct,
        reinstateProduct: ReinstateProduct,
        adjustStock: AdjustStock,
        addProductImage: AddProductImage,
        createCategory: CreateCategory,
        updateCategory: UpdateCategory,
        withdrawCategory: WithdrawCategory,
        reinstateCategory: ReinstateCategory,
    ): CatalogAdminHandlers =
        CatalogAdminHandlers(
            CatalogAdminUseCases(
                authorizeOperator,
                createProduct,
                updateProduct,
                withdrawProduct,
                reinstateProduct,
                adjustStock,
                addProductImage,
                createCategory,
                updateCategory,
                withdrawCategory,
                reinstateCategory,
            ),
        )

    @Bean
    fun reservationHandlers(
        reserveStock: ReserveStock,
        commitReservation: CommitReservation,
        releaseReservation: ReleaseReservation,
    ): ReservationHandlers = ReservationHandlers(reserveStock, commitReservation, releaseReservation)

    @Bean
    fun pricingHandlers(
        getPricing: GetPricing,
        getPricingBatch: GetPricingBatch,
    ): PricingHandlers = PricingHandlers(getPricing, getPricingBatch)

    @Bean
    fun catalogEventListeners(
        events: EventListenerSupport,
        settleOrderReservation: SettleOrderReservation,
    ): CatalogEventListeners = CatalogEventListeners(events, settleOrderReservation)

    @Bean
    fun reservationExpiryJob(
        expireReservations: ExpireReservations,
        properties: CatalogProperties,
    ): ReservationExpiryJob =
        ReservationExpiryJob(expireReservations, properties.expiryBatchSize, properties.expiryInterval)
}

package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.application.AuditLog
import com.ecommerce.catalog.application.Catalog
import com.ecommerce.catalog.application.CatalogSettings
import com.ecommerce.catalog.application.CategoryRepository
import com.ecommerce.catalog.application.CheckServiceHealth
import com.ecommerce.catalog.application.CommitReservation
import com.ecommerce.catalog.application.ExpireReservations
import com.ecommerce.catalog.application.GetCategory
import com.ecommerce.catalog.application.GetPricing
import com.ecommerce.catalog.application.GetPricingBatch
import com.ecommerce.catalog.application.GetProduct
import com.ecommerce.catalog.application.HealthProbe
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
import com.ecommerce.catalog.domain.ServiceName
import com.ecommerce.catalog.domain.ServiceNameResult
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wires the framework-free use cases onto the adapters of [AdapterConfiguration]. */
@Suppress("TooManyFunctions") // one bean per use case
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogProperties::class)
class UseCaseConfiguration {
    /** Every [HealthProbe] adapter in the context takes part. */
    @Bean
    fun checkServiceHealth(probes: List<HealthProbe>): CheckServiceHealth {
        val service =
            when (val result = ServiceName.of(SERVICE_NAME)) {
                is ServiceNameResult.Valid -> result.name
                is ServiceNameResult.Invalid -> error(result.reason)
            }
        return CheckServiceHealth(service, probes)
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    @Suppress("LongParameterList") // every port of the catalogue
    fun catalog(
        products: ProductRepository,
        categories: CategoryRepository,
        inventory: InventoryRepository,
        reservations: ReservationRepository,
        adjustments: StockAdjustmentRepository,
        events: StockEvents,
        transactions: Transactions,
        audit: AuditLog,
        properties: CatalogProperties,
        clock: Clock,
    ): Catalog =
        Catalog(
            products,
            categories,
            inventory,
            reservations,
            adjustments,
            events,
            transactions,
            audit,
            CatalogSettings(properties.currency, properties.reservationTtl, clock),
        )

    @Bean
    fun listProducts(catalog: Catalog): ListProducts = ListProducts(catalog)

    @Bean
    fun searchProducts(catalog: Catalog): SearchProducts = SearchProducts(catalog)

    @Bean
    fun getProduct(catalog: Catalog): GetProduct = GetProduct(catalog)

    @Bean
    fun listCategories(catalog: Catalog): ListCategories = ListCategories(catalog)

    @Bean
    fun getCategory(catalog: Catalog): GetCategory = GetCategory(catalog)

    @Bean
    fun getPricing(catalog: Catalog): GetPricing = GetPricing(catalog)

    @Bean
    fun getPricingBatch(catalog: Catalog): GetPricingBatch = GetPricingBatch(catalog)

    @Bean
    fun reserveStock(catalog: Catalog): ReserveStock = ReserveStock(catalog)

    @Bean
    fun commitReservation(catalog: Catalog): CommitReservation = CommitReservation(catalog)

    @Bean
    fun releaseReservation(catalog: Catalog): ReleaseReservation = ReleaseReservation(catalog)

    @Bean
    fun settleOrderReservation(catalog: Catalog): SettleOrderReservation = SettleOrderReservation(catalog)

    @Bean
    fun expireReservations(
        catalog: Catalog,
        properties: CatalogProperties,
    ): ExpireReservations = ExpireReservations(catalog, properties.expiryBatchSize)

    @Bean
    fun createProduct(catalog: Catalog): CreateProduct = CreateProduct(catalog)

    @Bean
    fun updateProduct(catalog: Catalog): UpdateProduct = UpdateProduct(catalog)

    @Bean
    fun withdrawProduct(catalog: Catalog): WithdrawProduct = WithdrawProduct(catalog)

    @Bean
    fun reinstateProduct(catalog: Catalog): ReinstateProduct = ReinstateProduct(catalog)

    @Bean
    fun adjustStock(catalog: Catalog): AdjustStock = AdjustStock(catalog)

    @Bean
    fun addProductImage(catalog: Catalog): AddProductImage = AddProductImage(catalog)

    @Bean
    fun createCategory(catalog: Catalog): CreateCategory = CreateCategory(catalog)

    @Bean
    fun updateCategory(catalog: Catalog): UpdateCategory = UpdateCategory(catalog)

    @Bean
    fun withdrawCategory(catalog: Catalog): WithdrawCategory = WithdrawCategory(catalog)

    @Bean
    fun reinstateCategory(catalog: Catalog): ReinstateCategory = ReinstateCategory(catalog)

    @Bean
    fun authorizeOperator(catalog: Catalog): AuthorizeOperator = AuthorizeOperator(catalog)

    private companion object {
        const val SERVICE_NAME = "catalog"
    }
}

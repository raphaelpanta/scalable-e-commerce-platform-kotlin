package com.ecommerce.catalog.application

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right
import com.ecommerce.catalog.domain.AccountId
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Reservation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The roles of an access token that matter to the catalogue. */
enum class CallerRole {
    SHOPPER,
    OPERATOR,
}

/** Who is asking: the account of a valid bearer token ([accountId], [roles]) or nobody ([ANONYMOUS]). */
data class Caller(
    val accountId: AccountId?,
    val roles: Set<CallerRole>,
) {
    /** True for an authenticated account holding the operator role. */
    val isOperator: Boolean get() = accountId != null && CallerRole.OPERATOR in roles

    companion object {
        val ANONYMOUS: Caller = Caller(null, emptySet())
    }
}

/**
 * Deployment settings: the platform [currency] every price must use, the lifetime of reservations (always later
 * than the 30-minute payment expiry), the [clock] and the id source.
 */
data class CatalogSettings(
    val currency: String = "BRL",
    val reservationTtl: Duration = Reservation.DEFAULT_TTL,
    val clock: Clock = Clock.systemUTC(),
    val ids: () -> UUID = UUID::randomUUID,
)

/** Every port a use case may need, behind one value, with small shared helpers. */
@Suppress("LongParameterList") // one port per kind of record, plus events, transactions, audit and settings
class Catalog(
    val products: ProductRepository,
    val categories: CategoryRepository,
    val inventory: InventoryRepository,
    val reservations: ReservationRepository,
    val adjustments: StockAdjustmentRepository,
    val events: StockEvents,
    val transactions: Transactions,
    val audit: AuditLog,
    val settings: CatalogSettings = CatalogSettings(),
) {
    /** Now, truncated to microseconds (the precision PostgreSQL keeps). */
    fun now(): Instant = settings.clock.instant().truncatedTo(ChronoUnit.MICROS)

    fun newId(): UUID = settings.ids()

    /**
     * The operator behind [caller], or [CatalogError.Forbidden] after recording the refused attempt at [action] on
     * [target] (US7 scenario 4).
     */
    suspend fun authorize(
        caller: Caller,
        action: String,
        target: UUID?,
    ): Either<CatalogError, AccountId> {
        val operator = caller.accountId?.takeIf { caller.isOperator }
        return if (operator != null) {
            operator.right()
        } else {
            audit.refused(caller, action, target)
            CatalogError.Forbidden(action).left()
        }
    }

    /** [currency] when it is the platform currency. */
    fun platformCurrency(currency: String): Either<FieldIssue, String> =
        if (currency == settings.currency) {
            currency.right()
        } else {
            FieldIssue("price.currency", "must be ${settings.currency}").left()
        }

    /** The available quantity (`onHand - reserved`) of each of [products]; 0 for a product without stock record. */
    suspend fun availability(products: Collection<Product>): Map<ProductId, Int> {
        val levels = if (products.isEmpty()) emptyMap() else inventory.findAll(products.map(Product::id))
        return products.associate { it.id to (levels[it.id]?.available ?: 0) }
    }

    /** What can be reserved of each of [productIds] now: the available quantity of active products, 0 otherwise. */
    suspend fun sellable(productIds: Collection<ProductId>): (ProductId) -> Int {
        val active = products.findAll(productIds).filter(Product::isActive)
        val available = availability(active)
        return { productId -> available[productId] ?: 0 }
    }
}

/** A single rejected input as a validation error. */
fun FieldIssue.invalid(): CatalogError.Invalid = CatalogError.Invalid(nonEmptyListOf(this))

/** A single-issue validation result in the accumulating shape. */
fun <T> Either<FieldIssue, T>.accumulating(): Either<NonEmptyList<FieldIssue>, T> = mapLeft { nonEmptyListOf(it) }

/** The concatenation of two lists of issues (the combine function of `zipOrAccumulate`). */
fun concatIssues(
    first: NonEmptyList<FieldIssue>,
    second: NonEmptyList<FieldIssue>,
): NonEmptyList<FieldIssue> = first + second

package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.application.ExpireReservations
import com.ecommerce.catalog.domain.Reservation
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `catalog.*` (application.yml): the platform currency and the reservation lifetime and expiry job. */
@ConfigurationProperties("catalog")
data class CatalogProperties(
    /** Currency every price must use (`PLATFORM_CURRENCY`). */
    val currency: String = "BRL",
    /** Lifetime of a reservation; always later than the 30-minute payment expiry of order. */
    val reservationTtl: Duration = Reservation.DEFAULT_TTL,
    /** How often reservations past their expiry are looked for. */
    val expiryInterval: Duration = Duration.ofMinutes(1),
    /** Reservations released per expiry run (a full batch runs again at once). */
    val expiryBatchSize: Int = ExpireReservations.DEFAULT_BATCH,
) {
    init {
        require(reservationTtl > PAYMENT_EXPIRY) { "catalog.reservation-ttl must exceed the 30-minute payment expiry" }
        require(expiryBatchSize > 0) { "catalog.expiry-batch-size must be positive" }
    }

    private companion object {
        val PAYMENT_EXPIRY: Duration = Duration.ofMinutes(30)
    }
}

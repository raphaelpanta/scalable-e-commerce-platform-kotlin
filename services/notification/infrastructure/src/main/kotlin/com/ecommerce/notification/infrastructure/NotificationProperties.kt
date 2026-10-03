package com.ecommerce.notification.infrastructure

import com.ecommerce.notification.application.RetentionPolicy
import com.ecommerce.notification.domain.RetryPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val DEFAULT_BATCH_SIZE = 20
private const val DEFAULT_MAX_ATTEMPTS = 5
private const val DEFAULT_INITIAL_DELAY_SECONDS = 30L
private const val DEFAULT_MAX_DELAY_MINUTES = 10L

/**
 * `notification.*` (application.yml): links, the identity client, the sender address, the delivery schedule and the
 * retention of notification content.
 */
@ConfigurationProperties("notification")
data class NotificationProperties(
    /** Base of the links in messages (`PUBLIC_BASE_URL`). */
    val publicBaseUrl: String = "http://localhost:8080",
    /** Base URL of the identity service (`IDENTITY_URL`). */
    val identityUrl: String = "http://localhost:8080",
    /** `X-Internal-Token` sent to identity (`INTERNAL_API_TOKEN`); blank sends none. */
    val internalToken: String = "",
    /** `From` address of every email. */
    val mailFrom: String = "notifications@ecommerce.example",
    val delivery: Delivery = Delivery(),
    val retention: Retention = Retention(),
) {
    /** `notification.delivery.*`: the delivery scheduler and the retry schedule (FR-019). */
    data class Delivery(
        /** `false` stops the scheduler (tests that only produce notifications). */
        val enabled: Boolean = true,
        val pollInterval: Duration = Duration.ofSeconds(1),
        val batchSize: Int = DEFAULT_BATCH_SIZE,
        val lease: Duration = Duration.ofMinutes(2),
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val initialDelay: Duration = Duration.ofSeconds(DEFAULT_INITIAL_DELAY_SECONDS),
        val maxDelay: Duration = Duration.ofMinutes(DEFAULT_MAX_DELAY_MINUTES),
    ) {
        /** The retry schedule these settings describe. */
        fun policy(): RetryPolicy = RetryPolicy(maxAttempts, initialDelay, maxDelay)
    }

    /**
     * `notification.retention.*` (data-model section 5, FR-007): terminal notifications (and their attempt history)
     * older than [notifications] are deleted every [purgeInterval], [batchSize] rows per statement.
     */
    data class Retention(
        val notifications: Duration = RetentionPolicy().retention,
        val batchSize: Int = RetentionPolicy().batchSize,
        val purgeInterval: Duration = Duration.ofHours(1),
    ) {
        fun policy(): RetentionPolicy = RetentionPolicy(notifications, batchSize)
    }
}

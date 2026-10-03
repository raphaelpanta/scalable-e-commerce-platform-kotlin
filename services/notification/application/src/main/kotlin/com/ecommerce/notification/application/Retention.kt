package com.ecommerce.notification.application

import java.time.Duration

/**
 * How long notification content is kept (data-model section 5, FR-007): [retention] after creation (90 days by
 * default) for terminal notifications, deleted in batches of [batchSize].
 */
data class RetentionPolicy(
    val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(!retention.isNegative) { "a retention cannot be negative" }
        require(batchSize >= 1) { "the purge batch holds at least one notification" }
    }

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 90L
        const val DEFAULT_BATCH_SIZE = 500
    }
}

/**
 * Deletes the terminal notifications (`sent`, `failed`, `suppressed`) older than the [policy]'s retention, with their
 * `delivery_attempts`, batch after batch until a batch comes back short; `queued` notifications are kept whatever
 * their age, since they are still being delivered. Returns how many notifications were deleted.
 */
class PurgeExpiredNotifications(
    private val notifications: NotificationRetentionRepository,
    private val clock: Clock,
    private val policy: RetentionPolicy = RetentionPolicy(),
) {
    suspend operator fun invoke(): Int {
        val cutoff = clock.now().minus(policy.retention)
        var total = 0
        do {
            val purged = notifications.deleteTerminalCreatedBefore(cutoff, policy.batchSize)
            total += purged
        } while (purged >= policy.batchSize)
        return total
    }
}

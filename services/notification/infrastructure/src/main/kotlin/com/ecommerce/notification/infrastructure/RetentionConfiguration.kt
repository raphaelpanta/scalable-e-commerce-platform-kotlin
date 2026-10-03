package com.ecommerce.notification.infrastructure

import com.ecommerce.notification.application.Clock
import com.ecommerce.notification.application.NotificationRetentionRepository
import com.ecommerce.notification.application.PurgeExpiredNotifications
import com.ecommerce.notification.infrastructure.jobs.NotificationPurgeJob
import com.ecommerce.notification.infrastructure.persistence.R2dbcNotificationRetention
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient

/** The retention purge of notification content (T140, data-model section 5, FR-007): adapter, use case and job. */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {
    @Bean
    fun notificationRetention(database: DatabaseClient): R2dbcNotificationRetention =
        R2dbcNotificationRetention(database)

    @Bean
    fun purgeExpiredNotifications(
        retention: NotificationRetentionRepository,
        clock: Clock,
        properties: NotificationProperties,
    ): PurgeExpiredNotifications = PurgeExpiredNotifications(retention, clock, properties.retention.policy())

    @Bean
    fun notificationPurgeJob(
        purge: PurgeExpiredNotifications,
        properties: NotificationProperties,
    ): NotificationPurgeJob = NotificationPurgeJob(purge, properties.retention.purgeInterval)
}

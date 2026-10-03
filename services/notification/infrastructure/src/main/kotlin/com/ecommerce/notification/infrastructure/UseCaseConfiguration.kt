package com.ecommerce.notification.infrastructure

import com.ecommerce.notification.application.AttemptDelivery
import com.ecommerce.notification.application.Clock
import com.ecommerce.notification.application.DeliveryOutcomePublisher
import com.ecommerce.notification.application.EmailSenderPort
import com.ecommerce.notification.application.ListFailed
import com.ecommerce.notification.application.ListOwn
import com.ecommerce.notification.application.NotificationRepository
import com.ecommerce.notification.application.ProduceNotificationFromEvent
import com.ecommerce.notification.application.RecipientLookupPort
import com.ecommerce.notification.application.RecipientReadModel
import com.ecommerce.notification.application.RetryFailed
import com.ecommerce.notification.application.SmsSenderPort
import com.ecommerce.notification.application.Transactions
import com.ecommerce.platform.messaging.PeriodicJob
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Wires the framework-free use cases (T091) onto the adapters of [AdapterConfiguration]. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {
    /** UTC now, at the microsecond precision PostgreSQL keeps. */
    @Bean
    fun notificationClock(): Clock = Clock { Instant.now().truncatedTo(ChronoUnit.MICROS) }

    @Bean
    fun produceNotificationFromEvent(
        notifications: NotificationRepository,
        recipients: RecipientReadModel,
        contacts: RecipientLookupPort,
        clock: Clock,
    ): ProduceNotificationFromEvent = ProduceNotificationFromEvent(notifications, recipients, contacts, clock)

    @Suppress("LongParameterList") // one adapter per port of the use case
    @Bean
    fun attemptDelivery(
        notifications: NotificationRepository,
        email: EmailSenderPort,
        sms: SmsSenderPort,
        contacts: RecipientLookupPort,
        outcomes: DeliveryOutcomePublisher,
        transactions: Transactions,
        clock: Clock,
        properties: NotificationProperties,
    ): AttemptDelivery =
        AttemptDelivery(
            notifications,
            AttemptDelivery.Channels(email, sms),
            contacts,
            outcomes,
            transactions,
            clock,
            AttemptDelivery.Settings(properties.delivery.policy(), properties.delivery.lease),
        )

    @Bean
    fun retryFailed(
        notifications: NotificationRepository,
        clock: Clock,
    ): RetryFailed = RetryFailed(notifications, clock)

    @Bean
    fun listOwn(notifications: NotificationRepository): ListOwn = ListOwn(notifications)

    @Bean
    fun listFailed(notifications: NotificationRepository): ListFailed = ListFailed(notifications)

    /**
     * The retry scheduler (FR-019): a coroutine loop of platform-messaging that runs a delivery round every
     * `notification.delivery.poll-interval`, at once again while rounds are full.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = ["notification.delivery.enabled"], matchIfMissing = true)
    fun deliveryScheduler(
        delivery: AttemptDelivery,
        properties: NotificationProperties,
    ): PeriodicJob {
        val batch = properties.delivery.batchSize
        return PeriodicJob("notification-delivery", properties.delivery.pollInterval) {
            delivery.deliverDue(batch) >= batch
        }
    }
}

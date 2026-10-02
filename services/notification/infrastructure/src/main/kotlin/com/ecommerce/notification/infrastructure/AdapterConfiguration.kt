package com.ecommerce.notification.infrastructure

import com.ecommerce.notification.application.Clock
import com.ecommerce.notification.application.ListFailed
import com.ecommerce.notification.application.ListOwn
import com.ecommerce.notification.application.ProduceNotificationFromEvent
import com.ecommerce.notification.application.RetryFailed
import com.ecommerce.notification.infrastructure.delivery.MailDelivery
import com.ecommerce.notification.infrastructure.delivery.SimulatedSmsSender
import com.ecommerce.notification.infrastructure.delivery.SmtpEmailSender
import com.ecommerce.notification.infrastructure.identity.IdentityContactClient
import com.ecommerce.notification.infrastructure.messaging.EventTriggers
import com.ecommerce.notification.infrastructure.messaging.NotificationEventHandler
import com.ecommerce.notification.infrastructure.messaging.NotificationEventListener
import com.ecommerce.notification.infrastructure.messaging.OutboxDeliveryOutcomePublisher
import com.ecommerce.notification.infrastructure.persistence.R2dbcNotificationRepository
import com.ecommerce.notification.infrastructure.persistence.R2dbcRecipientReadModel
import com.ecommerce.notification.infrastructure.persistence.R2dbcTransactions
import com.ecommerce.notification.infrastructure.web.NotificationHandlers
import com.ecommerce.notification.infrastructure.web.notificationRoutes
import com.ecommerce.platform.http.WebClientDefaults
import com.ecommerce.platform.messaging.consumer.EventListenerSupport
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/** The adapters behind the application ports (T092, T093, T094). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties::class)
class AdapterConfiguration {
    @Bean
    fun notificationRepository(database: DatabaseClient): R2dbcNotificationRepository =
        R2dbcNotificationRepository(database)

    @Bean
    fun recipientReadModel(
        database: DatabaseClient,
        clock: Clock,
    ): R2dbcRecipientReadModel = R2dbcRecipientReadModel(database, clock)

    @Bean
    fun notificationTransactions(transactionManager: ReactiveTransactionManager): R2dbcTransactions =
        R2dbcTransactions(TransactionalOperator.create(transactionManager))

    @Bean
    fun identityContactClient(
        builder: ObjectProvider<WebClient.Builder>,
        properties: NotificationProperties,
    ): IdentityContactClient =
        IdentityContactClient(
            WebClientDefaults.internalClient(
                builder.getIfAvailable(WebClient::builder),
                properties.identityUrl,
                properties.internalToken,
            ),
        )

    @Bean
    fun mailDelivery(
        mail: JavaMailSender,
        properties: NotificationProperties,
    ): MailDelivery = MailDelivery(mail, properties.mailFrom)

    @Bean
    fun emailSender(delivery: MailDelivery): SmtpEmailSender = SmtpEmailSender(delivery)

    @Bean
    fun smsSender(delivery: MailDelivery): SimulatedSmsSender = SimulatedSmsSender(delivery)

    @Bean
    fun deliveryOutcomePublisher(
        outbox: OutboxPublisher,
        envelopes: EnvelopeFactory,
    ): OutboxDeliveryOutcomePublisher = OutboxDeliveryOutcomePublisher(outbox, envelopes)

    @Bean
    fun notificationEventListener(
        events: EventListenerSupport,
        produce: ProduceNotificationFromEvent,
        properties: NotificationProperties,
    ): NotificationEventListener =
        NotificationEventListener(events, NotificationEventHandler(EventTriggers(properties.publicBaseUrl), produce))

    @Bean
    fun notificationRouter(
        ownNotifications: ListOwn,
        failedNotifications: ListFailed,
        retryFailed: RetryFailed,
    ): RouterFunction<ServerResponse> =
        notificationRoutes(NotificationHandlers(ownNotifications, failedNotifications, retryFailed))
}

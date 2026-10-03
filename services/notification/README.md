# notification

Service for the bounded context **notification** (feature 004, user story 6, FR-018..FR-020): it consumes account,
order and payment events, produces one notification per event and permitted channel, delivers them by email
(SMTP, Mailpit locally) and by a simulated SMS channel, retries failed deliveries and shows them to operators.

Package root: `com.ecommerce.notification`.

| Module | Contents |
|---|---|
| `domain` | `Notification` and its delivery state machine, `DeliveryAttempt`, `RetryPolicy`, `ChannelSelection`, `NotificationPlanner`, `Recipient` read model, value objects, `Templates` (pure functions) |
| `application` | ports (`NotificationRepository`, `RecipientReadModel`, `RecipientLookupPort`, `EmailSenderPort`, `SmsSenderPort`, `DeliveryOutcomePublisher`, `Transactions`, `Clock`), the `Caller` of the operator use cases and use cases `ProduceNotificationFromEvent`, `AttemptDelivery`, `RetryFailed`, `ListOwn`, `ListFailed`, `PurgeExpiredNotifications` |
| `infrastructure` | R2DBC repositories (`V2__notification_schema.sql`, `V3__deferred_recipient_and_retention.sql`), Kafka listener (group `notification`, idempotent by `eventId`), identity contact client, SMTP and simulated SMS senders, outbox publisher of `NotificationSent`/`NotificationFailed`, delivery scheduler, retention purge job, public API |

## Behaviour

| Event (topic) | Notification kind (API `type`) | Channels |
|---|---|---|
| `AccountRegistered` (`identity.account.v1`) | `account_verification` (`registration_verification`), link `${PUBLIC_BASE_URL}/verify?token=...` | email only |
| `AccountVerified` | none: the recipient read model records the verified address | |
| `PasswordResetRequested` | `password_reset`, link `${PUBLIC_BASE_URL}/reset-password?token=...` | email only |
| `AccountDeleted` | none: recipient anonymised, queued notifications `suppressed`, addresses and bodies cleared | |
| `OrderPaid` (`order.order.v1`) | `order_confirmation`: order number and id, lines, total, delivery address | email, SMS |
| `OrderPaymentFailed` | `payment_failure`: decline category | email, SMS |
| `OrderShipped` / `OrderDelivered` | `order_shipped` / `order_delivered` | email, SMS |
| `OrderCancelled` | `order_cancelled`: reason, refund notice | email, SMS |
| `RefundRecorded` (`payment.payment.v1`) | `refund_confirmation`: amount | email, SMS |

Contact details and preferences are read from identity (`GET /internal/accounts/{accountId}/contact`) when the event
is processed; when identity cannot answer, the event's `recipient` snapshot is used. When identity cannot answer and
the event has no snapshot, the event is still consumed (never dead-lettered): one email is stored `queued` with
`awaiting_recipient`, and the delivery job looks the contact up again before sending it (suppressed if the contact no
longer permits email, failed at once if identity no longer knows the account). While identity stays down, each lookup
counts as a failed attempt of the retry schedule, so a long outage ends `failed` on the operator view, where an
operator retry re-queues it. Such a deferred message goes by email only (the SMS preference could not be read); the
two security kinds, `AccountRegistered` and `PasswordResetRequested`, are email only anyway, so they are never lost.
Email goes out when `channels` contains `email` (always for the two security kinds); SMS when `channels` contains
`sms` and the phone is verified. An anonymised account gets one `suppressed` record and nothing is sent.

Delivery: `queued` notifications are claimed by a coroutine job (`notification.delivery.poll-interval`, default 1 s)
under a lease (`FOR UPDATE SKIP LOCKED`); 5 attempts, waiting 30 s, 60 s, 120 s, 240 s (doubling, capped at 10 min),
then `failed` with `NotificationFailed`; a success publishes `NotificationSent`. Every attempt is a row of
`delivery_attempts`. An operator retry re-queues a failed notification with a fresh budget.

Authorisation: the shopper listing needs the `shopper` role; `listFailedNotifications` and `retryFailedNotification`
receive the caller and refuse anyone without the `operator` role in the use case itself (`Forbidden`, answered 403),
before reading anything.

Retention (data-model §5, FR-007): `NotificationPurgeJob` runs at start-up and every
`notification.retention.purge-interval` (1 h) and deletes `sent`, `failed` and `suppressed` notifications created more
than `notification.retention.notifications` (90 days) ago, `notification.retention.batch-size` (500) rows per
statement, with their `delivery_attempts` (`ON DELETE CASCADE`); `queued` notifications are never purged.

SMS simulator (docs/service-conventions.md §8): each SMS is mirrored to Mailpit as an email to
`sms-<E.164 digits>@sms.ecommerce.invalid` with subject `SMS to <phone>` and the SMS text as body, then recorded.

## Blocking exception

JavaMail (`JavaMailSender`) has no non-blocking API. `MailDelivery` runs every send inside
`withContext(Dispatchers.IO)`, never on a Netty or Reactor event loop; its only caller is the delivery scheduler,
so no request path waits for SMTP. This is the third documented blocking call of the platform, after Flyway at
start-up and the Kafka listener thread of platform-messaging (docs/build.md "Blocking exception").

## Running

Environment: `NOTIFICATION_DB_HOST` (default `localhost`), `NOTIFICATION_DB_USER`, `NOTIFICATION_DB_PASSWORD`,
`KAFKA_BOOTSTRAP_SERVERS`, `IDENTITY_URL`, `INTERNAL_API_TOKEN`, `SMTP_HOST`, `SMTP_PORT`, `PUBLIC_BASE_URL`,
`JWKS_URI`. Then `./gradlew -q :services:notification:infrastructure:bootRun`. Health on
`http://localhost:8081/actuator/health`.

| Layer | Where | Run alone |
|---|---|---|
| unit | `domain/src/test`, `application/src/test`, architecture rules in `infrastructure` | `./gradlew -q :services:notification:domain:test` |
| integration | `infrastructure/src/integrationTest` (`DeliveryIT`, `DeferredRecipientIT`, `NotificationPurgeIT`: PostgreSQL, Kafka, Mailpit, WireMock identity) | `./gradlew -q :services:notification:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest` (consumers of identity, order, payment; provider `NotificationProviderVerificationTest`) | `./gradlew -q :services:notification:infrastructure:contractTest :services:notification:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` (Cucumber) | `./gradlew -q :services:notification:infrastructure:acceptanceTest` |

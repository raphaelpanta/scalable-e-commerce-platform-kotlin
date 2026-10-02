# Tasks: E-Commerce Platform MVP

**Input**: Design documents from `/specs/004-ecommerce-platform-mvp/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md. Features 001 (harness hooks), 002 (Gradle monorepo bootstrap with `build-logic/` convention plugins and `gradle/libs.versions.toml`) and 003 (public repository) are assumed implemented first; tasks below reference the convention plugin names those features define (`kotlin-domain`, `kotlin-application`, `kotlin-service`, `pact`, `pitest`).

**Tests**: Included and mandatory. Constitution Principle V requires property-based unit tests, Testcontainers/WireMock integration tests, Pact contract tests and behaviour-level Cucumber acceptance tests, written before or alongside implementation, plus Pitest mutation score ≥ 80 % on `domain` and `application` modules.

**Organization**: Tasks are grouped by user story (US1–US9 from spec.md) so each story is an independently testable increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US9)
- Include exact file paths in descriptions

## Path Conventions

- Service `<ctx>` ∈ {identity, catalog, cart, order, payment, notification}; modules `services/<ctx>/domain`, `services/<ctx>/application`, `services/<ctx>/infrastructure`; Kotlin package root `com.ecommerce.<ctx>` (the prefix the feature 002 generator and Konsist rules use; `com.shop` in the original task text is superseded).
- Source sets per module: `src/main/kotlin`, `src/test/kotlin` (unit/property), and in `infrastructure` also `src/integrationTest/kotlin`, `src/contractTest/kotlin`, `src/acceptanceTest/kotlin`.
- Gateway: `services/gateway/src/main/kotlin/com/ecommerce/gateway`.
- Shared libraries (no domain logic, Principle VI): `libs/platform-core` (Problem+JSON, correlation, Either helpers), `libs/platform-messaging` (event envelope, outbox relay, processed-event store, Kafka serialization).
- Platform: `platform/compose`, `platform/docker`, `platform/observability`, `platform/ci-runner`; contracts at `contracts/`; Cucumber features at `acceptance/src/test/resources/features`; workflows at `.github/workflows`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the repository layout the plan defines on top of the bootstrap from feature 002.

- [X] T001 Register all modules in `settings.gradle.kts`: `libs:platform-core`, `libs:platform-messaging`, `services:gateway`, and `services:<ctx>:{domain,application,infrastructure}` for identity, catalog, cart, order, payment, notification, plus `acceptance`
- [X] T002 [P] Add catalogue entries in `gradle/libs.versions.toml` for Spring Boot 4.1.x BOM, Spring Cloud 2025.1.x BOM (`spring-cloud-starter-gateway-server-webflux`), kotlinx-coroutines, Spring Data R2DBC + `r2dbc-postgresql`, Flyway + `postgresql` JDBC, Spring Kafka, Micrometer/OpenTelemetry, Arrow core, Kotest 6.2.x (+ property, Spring extension), MockK, Testcontainers 2.0.x (postgresql, kafka), WireMock, Pact JVM 4.7.x (consumer + provider), Cucumber JVM 7.34.x, Konsist 0.17.x, Pitest 1.30.x
- [X] T003 [P] Create `build.gradle.kts` for every `services/<ctx>/domain` applying `kotlin-domain` (no Spring, Arrow + kotlinx-datetime only) and every `services/<ctx>/application` applying `kotlin-application` (depends on its `domain`)
- [X] T004 [P] Create `build.gradle.kts` for every `services/<ctx>/infrastructure` applying `kotlin-service`, `pact`, `pitest` (depends on `domain`, `application`, `libs:platform-core`, `libs:platform-messaging`) and declaring the `integrationTest`, `contractTest`, `acceptanceTest` source sets
- [X] T005 [P] Copy the design contracts into the repository as the source of truth: `contracts/openapi/*.yaml`, `contracts/asyncapi/events.yaml` (from `specs/004-ecommerce-platform-mvp/contracts/`) and add `contracts/README.md` explaining the additive-within-v1 evolution rule
- [X] T006 [P] Create the shared multi-stage Dockerfile template `platform/docker/Dockerfile.service` (Gradle wrapper build stage with BuildKit cache mounts → Spring Boot layer extraction → JRE 25 runtime, non-root user, HEALTHCHECK on `/actuator/health/readiness`) and `platform/docker/.dockerignore`
- [X] T007 [P] Create `platform/compose/docker-compose.yml` with profiles `core` (gateway, six services, one `postgres` per service, `kafka` in KRaft mode, `mailpit`) and `observability` (otel-collector, loki, tempo, prometheus, grafana), plus `platform/compose/.env.example` and per-service `platform/compose/env/<ctx>.env`
- [X] T008 [P] Create `acceptance/build.gradle.kts` (Cucumber JVM + Kotest assertions, runs against `GATEWAY_URL`) and the empty step-definition package `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/`
- [X] T009 [P] Create `frontend/README.md` stating the directory is reserved for the storefront feature and excluded from the build

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Cross-cutting libraries, security plumbing, messaging infrastructure and the gateway skeleton that every story needs.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T010 [P] Implement RFC 9457 `Problem` model, `ProblemType` URIs (`insufficient-stock`, `price-changed`, `stale-revision`, `validation`, `not-found`, `conflict`, `throttled`) and a WebFlux `ProblemExceptionHandler` in `libs/platform-core/src/main/kotlin/com/ecommerce/platform/problem/`
- [X] T011 [P] Implement `CorrelationId` value object (UUID-shaped validation) and a WebFilter that reads `X-Correlation-Id`, replaces malformed values and records the original, echoes the header, and binds it to the MDC and OpenTelemetry baggage in `libs/platform-core/src/main/kotlin/com/ecommerce/platform/correlation/`
- [X] T012 [P] Implement shared value objects `Money` (amountMinor: Long, currency ISO-4217, same-currency arithmetic, overflow rejected), `Email`, `PhoneNumber`, `Quantity` (1..99), `PostalAddress` and Arrow `Either` result helpers in `libs/platform-core/src/main/kotlin/com/ecommerce/platform/values/` with Kotest property tests and `Arb` generators in `libs/platform-core/src/test/kotlin/com/ecommerce/platform/values/`
- [X] T013 [P] Implement the event `Envelope` (eventId, type, version=1, occurredAt, aggregateId, correlationId, producer, payload), JSON serialization and topic naming `<context>.<aggregate>.v1` per `contracts/asyncapi/events.yaml` in `libs/platform-messaging/src/main/kotlin/com/ecommerce/platform/messaging/envelope/`
- [X] T014 [P] Implement the transactional outbox (`outbox` table schema fragment, `OutboxPublisher` port, R2DBC store, polling relay to Kafka keyed by aggregateId) in `libs/platform-messaging/src/main/kotlin/com/ecommerce/platform/messaging/outbox/` with a Testcontainers (PostgreSQL + Kafka) integration test in `libs/platform-messaging/src/integrationTest/kotlin/`
- [X] T015 [P] Implement the idempotent consumer support (`processed_event` table with eventId, 7-day retention purge, `IdempotentConsumer` wrapper, Kafka listener container factory with manual acks) in `libs/platform-messaging/src/main/kotlin/com/ecommerce/platform/messaging/consumer/` with a duplicate-delivery integration test
- [X] T016 [P] Implement the shared Spring Security resource-server configuration (JWT via JWKS URI, roles `shopper`/`operator` from claims, deny-by-default `authorizeExchange`, security headers, CORS allow-list) as an auto-configuration in `libs/platform-core/src/main/kotlin/com/ecommerce/platform/security/`
- [X] T017 [P] Implement structured JSON logging (service, traceId, spanId, correlationId; PII fields masked by a `@Pii` marker) and OpenTelemetry OTLP exporter defaults in `libs/platform-core/src/main/resources/META-INF/spring/` and `libs/platform-core/src/main/kotlin/com/ecommerce/platform/observability/`
- [X] T018 [P] Create the Testcontainers base fixtures (`PostgresContainerBase`, `KafkaContainerBase` for `apache/kafka`, `WireMockBase`) in `libs/platform-core/src/testFixtures/kotlin/com/ecommerce/platform/testing/`
- [X] T019 [P] Create the Konsist architecture test template (domain has no Spring/R2DBC/Kafka imports; application depends only on domain and Arrow; infrastructure may depend on both) in `libs/platform-core/src/testFixtures/kotlin/com/ecommerce/platform/testing/ArchitectureRules.kt` and apply it in each `services/<ctx>/infrastructure/src/test/kotlin/com/ecommerce/<ctx>/ArchitectureTest.kt`
- [X] T020 Create the gateway application skeleton `services/gateway/src/main/kotlin/com/ecommerce/gateway/GatewayApplication.kt` with routes from `contracts/gateway-routes.md` (`/api/v1/identity/**`, `/api/v1/catalog/**`, `/api/v1/cart/**`, `/api/v1/orders/**`, `/api/v1/payments/**`, `/api/v1/notifications/**` → `http://<service>:8080`), correlation filter, JWT validation via identity JWKS, per-tier rate limiting, 404 for unknown routes, request size limit, in `services/gateway/src/main/resources/application.yml`
- [X] T021 Write gateway integration tests (WireMock upstreams) for unknown-route 404, missing/invalid JWT 401 on protected prefixes, anonymous pass-through on catalogue reads and cart, correlation-id replacement, rate-limit 429 in `services/gateway/src/integrationTest/kotlin/com/ecommerce/gateway/GatewayRoutingIT.kt`
- [X] T022 [P] Create per-service Spring Boot application classes and `application.yml` (server port 8080, R2DBC URL, Flyway JDBC URL, Kafka bootstrap, OTLP endpoint, JWKS URI) for identity, catalog, cart, order, payment, notification in `services/<ctx>/infrastructure/src/main/kotlin/com/ecommerce/<ctx>/<Ctx>Application.kt` and `services/<ctx>/infrastructure/src/main/resources/application.yml`
- [X] T023 [P] Create the Flyway baseline migration per service (`V1__init.sql` with the outbox and processed_event tables from T014/T015) in `services/<ctx>/infrastructure/src/main/resources/db/migration/`
- [X] T024 [P] Create `services/<ctx>/infrastructure/Dockerfile` for each service and the gateway from the template in `platform/docker/Dockerfile.service` and wire `build:` contexts in `platform/compose/docker-compose.yml`
- [X] T025 [P] Create `platform/observability/otel-collector.yaml` (OTLP receivers; exporters to Loki, Tempo, Prometheus), `platform/observability/grafana/provisioning/datasources.yaml` and a correlation-id search dashboard in `platform/observability/grafana/dashboards/requests-by-correlation.json`
- [X] T026 [P] Add the Pact Broker and the simulated SMS sink to `platform/compose/docker-compose.yml` under profile `ci`, and configure `pact { broker }` publication settings in the `pact` convention usage of each infrastructure module (`PACT_BROKER_URL` env)
- [X] T027 Create the Cucumber runner `acceptance/src/test/kotlin/com/ecommerce/acceptance/RunCucumberTest.kt`, shared world/HTTP client with `X-Correlation-Id`, `Idempotency-Key` and bearer handling in `acceptance/src/test/kotlin/com/ecommerce/acceptance/support/ApiClient.kt`, and a Mailpit reader in `acceptance/src/test/kotlin/com/ecommerce/acceptance/support/MailpitClient.kt`
- [ ] T028 Create the seed-data profile (operator account, 3 categories, 20 products with stock, one withdrawn product) as Flyway `R__seed.sql` under a `seed` Spring profile in `services/identity/infrastructure/src/main/resources/db/seed/` and `services/catalog/infrastructure/src/main/resources/db/seed/`, enabled via `SEED=true` in `platform/compose/docker-compose.yml`

**Checkpoint**: Foundation ready — `docker compose --profile core up` starts every service with health checks green; gateway routing tests pass.

---

## Phase 3: User Story 1 - Browse the catalogue without an account (Priority: P1) 🎯 MVP

**Goal**: Anonymous listing, category browsing, search and product detail with availability.

**Independent Test**: With seed data, list products in a category, search a term, open a product and a zero-stock product through the gateway anonymously; results match the seed and withdrawn products never appear (spec US1 scenarios 1–4).

### Tests for User Story 1

- [ ] T029 [P] [US1] Property tests for `Product`, `Category`, `InventoryLevel`, `Sku`, `ProductName` value objects (name non-blank ≤ 200, price > 0, availability = available − reserved ≥ 0, withdrawn products not addable) in `services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/`
- [ ] T030 [P] [US1] Integration tests for product/category R2DBC repositories and search (case-insensitive name match, category filter, paging max 100, withdrawn excluded) in `services/catalog/infrastructure/src/integrationTest/kotlin/com/ecommerce/catalog/CatalogQueryIT.kt`
- [ ] T031 [P] [US1] Pact provider verification for `listProducts`, `getProduct`, `listCategories`, `getCategory` against `contracts/openapi/catalog.yaml` in `services/catalog/infrastructure/src/contractTest/kotlin/com/ecommerce/catalog/CatalogProviderPactTest.kt`
- [X] T032 [P] [US1] Cucumber feature `acceptance/src/test/resources/features/catalogue-browsing.feature` (browse a category, search, view an out-of-stock product, request an unknown product) written in business language, plus steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/CatalogueSteps.kt`

### Implementation for User Story 1

- [ ] T033 [P] [US1] Implement domain model `Product` (id, sku, name, description, images, price: Money, categoryId, saleState active|withdrawn), `Category` (id, name, parentId?), `InventoryLevel` (productId, available, reserved) with invariants per data-model §3.2 in `services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/`
- [ ] T034 [P] [US1] Define ports `ProductRepository`, `CategoryRepository`, `InventoryRepository` and queries `ListProducts`, `SearchProducts`, `GetProduct`, `ListCategories`, `GetCategory` (paged, withdrawn excluded for shoppers, `availableQuantity` only for operators) in `services/catalog/application/src/main/kotlin/com/ecommerce/catalog/application/`
- [ ] T035 [US1] Implement Flyway `V2__catalog_schema.sql` (products, categories, inventory_levels, product_images) and R2DBC adapters for the ports in `services/catalog/infrastructure/src/main/kotlin/com/ecommerce/catalog/infrastructure/persistence/`
- [ ] T036 [US1] Implement WebFlux coroutine handlers for `listProducts`, `getProduct`, `listCategories`, `getCategory` with Problem+JSON errors and `{items,page,size,totalItems}` paging per `contracts/openapi/catalog.yaml` in `services/catalog/infrastructure/src/main/kotlin/com/ecommerce/catalog/infrastructure/web/CatalogQueryHandlers.kt`
- [ ] T037 [US1] Expose readiness/liveness and request metrics for catalog (`/actuator/health`, `/actuator/prometheus`) in `services/catalog/infrastructure/src/main/resources/application.yml` and verify the gateway route `/api/v1/catalog/**` in `services/gateway/src/main/resources/application.yml`

**Checkpoint**: US1 passes its feature file through the gateway on the Compose `core` stack.

---

## Phase 4: User Story 2 - Build a cart anonymously and keep it after signing in (Priority: P1)

**Goal**: Anonymous cart with `X-Cart-Token`, quantity rules, revision, price-change flags, and merge on sign-in.

**Independent Test**: Add two products anonymously, sign in to an account with one line, merge, and confirm summed quantities capped at stock, correct total and a changed `revision` (spec US2 scenarios 1–5). Requires US3 only for the sign-in step; the anonymous scenarios run without it.

### Tests for User Story 2

- [X] T038 [P] [US2] Property tests for `Cart` aggregate (add/update/remove, quantity 1..99, total = Σ priceAtAdd × quantity, `revision` changes on every mutating operation and on price refresh, merge sums quantities and caps at available stock reporting capped lines) in `services/cart/domain/src/test/kotlin/com/ecommerce/cart/domain/CartSpec.kt`
- [X] T039 [P] [US2] Pact consumer test cart → catalog for price/availability lookup (in stock, out of stock, withdrawn, unknown product) in `services/cart/infrastructure/src/contractTest/kotlin/com/ecommerce/cart/CatalogClientPactTest.kt`
- [X] T040 [P] [US2] Integration tests for cart persistence, `X-Cart-Token` issuance, merge consuming the anonymous cart (second merge → 404), 30-day idle purge in `services/cart/infrastructure/src/integrationTest/kotlin/com/ecommerce/cart/CartIT.kt`
- [X] T041 [P] [US2] Pact provider verification for `getCart`, `addCartLine`, `updateCartLineQuantity`, `removeCartLine`, `clearCart`, `mergeCart` against `contracts/openapi/cart.yaml` in `services/cart/infrastructure/src/contractTest/kotlin/com/ecommerce/cart/CartProviderPactTest.kt`
- [X] T042 [P] [US2] Cucumber feature `acceptance/src/test/resources/features/shopping-cart.feature` (add and change quantities, remove, more than stock refused, price changed flag, merge on sign-in) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/CartSteps.kt`

### Implementation for User Story 2

- [X] T043 [P] [US2] Implement domain `Cart` (id, owner: AnonymousToken|AccountId, lines, `revision: CartRevision`, updatedAt), `CartLine` (productId, sku, nameSnapshot, quantity: Quantity, priceAtAdd: Money, addedAt), `CartRevision` and merge rules per data-model §3.3 in `services/cart/domain/src/main/kotlin/com/ecommerce/cart/domain/`
- [X] T044 [P] [US2] Define ports `CartRepository`, `CatalogPricingPort` (price + availability) and use cases `GetCart` (returns currentPrice, priceChanged flag, lineTotal, total), `AddLine`, `UpdateLineQuantity`, `RemoveLine`, `ClearCart`, `MergeCarts` in `services/cart/application/src/main/kotlin/com/ecommerce/cart/application/`
- [X] T045 [US2] Implement Flyway `V2__cart_schema.sql`, R2DBC `CartRepository`, WebClient `CatalogPricingAdapter` (non-blocking, DNS load-balanced to `http://catalog:8080`) in `services/cart/infrastructure/src/main/kotlin/com/ecommerce/cart/infrastructure/`
- [X] T046 [US2] Implement handlers for all cart operations with `X-Cart-Token` issuance/validation (opaque 256-bit random), optional bearer, 422 with available quantity on stock cap, and `revision` in every response per `contracts/openapi/cart.yaml` in `services/cart/infrastructure/src/main/kotlin/com/ecommerce/cart/infrastructure/web/CartHandlers.kt`
- [X] T047 [US2] Implement outbox publication of `CartMerged` and the consumers for `OrderPaid` (clear ordered lines) and `AccountDeleted` (delete account cart) using `libs/platform-messaging` in `services/cart/infrastructure/src/main/kotlin/com/ecommerce/cart/infrastructure/messaging/`
- [X] T048 [US2] Add the scheduled purge of anonymous carts idle for 30 days in `services/cart/infrastructure/src/main/kotlin/com/ecommerce/cart/infrastructure/jobs/CartPurgeJob.kt`

**Checkpoint**: US1 and US2 feature files pass; cart contract verified against catalog pact.

---

## Phase 5: User Story 3 - Register, sign in and manage the account (Priority: P1)

**Goal**: Email/password identity with verification, sessions, throttling, reset, profile, addresses, notification preferences, phone verification, anonymisation.

**Independent Test**: Register, read the verification mail in Mailpit, verify, sign in, add an address, sign out and in, address persists; checkout while signed out is refused (spec US3 scenarios 1–6).

### Tests for User Story 3

- [X] T049 [P] [US3] Property tests for `Account` aggregate and value objects (password policy 12–128 chars and not equal to email, verification/reset tokens single-use with 24 h / 1 h TTL, throttle after 5 consecutive failures for 15 min reset on success, roles shopper|operator, anonymisation replaces email with pseudonym and clears profile/addresses) in `services/identity/domain/src/test/kotlin/com/ecommerce/identity/domain/`
- [X] T050 [P] [US3] Integration tests for registration → verification → sign-in → refresh → sign-out flows, Argon2id hashing, JWKS endpoint, throttling, reset flow with Testcontainers PostgreSQL in `services/identity/infrastructure/src/integrationTest/kotlin/com/ecommerce/identity/IdentityFlowsIT.kt`
- [X] T051 [P] [US3] Pact provider verification for all 18 identity operations against `contracts/openapi/identity.yaml` in `services/identity/infrastructure/src/contractTest/kotlin/com/ecommerce/identity/IdentityProviderPactTest.kt`, and consumer test gateway → identity JWKS in `services/gateway/src/contractTest/kotlin/com/ecommerce/gateway/IdentityJwksPactTest.kt`
- [X] T052 [P] [US3] Cucumber feature `acceptance/src/test/resources/features/account.feature` (register and verify, duplicate email, wrong password five times, address persists, forgotten password, checkout while signed out) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/AccountSteps.kt`

### Implementation for User Story 3

- [X] T053 [P] [US3] Implement domain `Account` (id, email, passwordHash, status unverified|active|deleted, roles, pseudonym), `Address`, `VerificationToken`, `PasswordResetToken`, `SessionRecord`, `NotificationPreference` (channels email|sms, phone, phoneVerified), `PhoneVerification` with invariants per data-model §3.1 in `services/identity/domain/src/main/kotlin/com/ecommerce/identity/domain/`
- [X] T054 [P] [US3] Define ports (`AccountRepository`, `TokenRepository`, `SessionRepository`, `PasswordHasher`, `TokenSigner`, `Clock`) and use cases `RegisterAccount`, `VerifyEmail`, `SignIn` (throttle), `RefreshSession`, `SignOut`, `RequestPasswordReset`, `CompletePasswordReset`, `GetProfile`, `UpdateProfile`, `DeleteAccount`, address CRUD, `GetNotificationPreferences`, `UpdateNotificationPreferences`, `RequestPhoneVerification`, `ConfirmPhoneVerification` in `services/identity/application/src/main/kotlin/com/ecommerce/identity/application/`
- [X] T055 [US3] Implement Flyway `V2__identity_schema.sql`, R2DBC repositories, Argon2id `PasswordHasher`, EdDSA `TokenSigner` (access 15 min, rotating opaque refresh 30 days, revocable) and the `/.well-known/jwks.json` endpoint in `services/identity/infrastructure/src/main/kotlin/com/ecommerce/identity/infrastructure/`
- [X] T056 [US3] Implement handlers for `registerAccount` (generic 202), `verifyEmail`, `signIn`, `refreshSession`, `signOut`, `requestPasswordReset` (generic 202), `completePasswordReset`, profile, addresses, preferences, phone verification, `deleteOwnAccount` per `contracts/openapi/identity.yaml` in `services/identity/infrastructure/src/main/kotlin/com/ecommerce/identity/infrastructure/web/IdentityHandlers.kt`
- [X] T057 [US3] Implement outbox publication of `AccountRegistered` (with verification token marked sensitive), `AccountVerified`, `PasswordResetRequested`, `AccountDeleted` per `contracts/asyncapi/events.yaml` in `services/identity/infrastructure/src/main/kotlin/com/ecommerce/identity/infrastructure/messaging/IdentityEventPublisher.kt`
- [X] T058 [US3] Configure gateway JWT validation against `http://identity:8080/.well-known/jwks.json` and the `auth` rate-limit tier for `/api/v1/identity/sessions` and `/api/v1/identity/password-resets` in `services/gateway/src/main/resources/application.yml`

**Checkpoint**: US1–US3 pass; signed-in shoppers can merge carts (US2 scenario 3 now fully testable).

---

## Phase 6: User Story 4 - Place an order and pay (Priority: P1)

**Goal**: Idempotent checkout with cart-revision check, synchronous stock reservation, simulated payment, two-status order, cart cleared on success.

**Independent Test**: Approved checkout → order `placed`/`approved`, stock decreased, cart empty, confirmation event; declined checkout → order `cancelled`/`failed`, stock released, cart kept; concurrent last-unit race → exactly one success; same Idempotency-Key → one order; stale cart revision → 409 price-changed (spec US4 scenarios 1–7).

### Tests for User Story 4

- [X] T059 [P] [US4] Property tests for `Order` aggregate (orderStatus transitions placed→preparing→shipped→delivered, cancelled only from placed|preparing; paymentStatus pending→approved|failed; preparing requires approved; failed or 30-min pending → cancelled with PAYMENT_FAILED|PAYMENT_EXPIRED; lines frozen; lineTotal = unitPrice × quantity; StatusChange append-only) in `services/order/domain/src/test/kotlin/com/ecommerce/order/domain/OrderSpec.kt`
- [ ] T060 [P] [US4] Property tests for catalog `Reservation` (reserve fails when available < requested, available never negative, commit/release idempotent, expiry) in `services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/ReservationSpec.kt`
- [X] T061 [P] [US4] Property tests for `SimulatedPaymentProvider` rules (default approved; `tok_sim_unreachable` → pending; amount ending 13 → insufficient_funds; ending 14 → card_expired; prefix `tok_sim_decline` → card_rejected; refunds succeed) in `services/payment/infrastructure/src/test/kotlin/com/ecommerce/payment/infrastructure/provider/SimulatedPaymentProviderSpec.kt`
- [X] T062 [P] [US4] Pact consumer tests order → cart (read cart with revision), order → catalog (reserve success, insufficient stock listing lines, commit, release), order → payment (authorise approved/declined/pending) in `services/order/infrastructure/src/contractTest/kotlin/com/ecommerce/order/`; message pacts payment → order for `PaymentApproved`, `PaymentDeclined`, `PaymentPending`
- [X] T063 [P] [US4] Integration test for concurrent checkout of the last unit (two parallel requests, exactly one 201, stock never negative) and for idempotency (same key same body → same response; same key different body → 422) with Testcontainers PostgreSQL + Kafka in `services/order/infrastructure/src/integrationTest/kotlin/com/ecommerce/order/CheckoutConcurrencyIT.kt`
- [X] T064 [P] [US4] Pact provider verification for `placeOrder` (201, 202, 409 insufficient-stock, 409 price-changed, 422 declined) against `contracts/openapi/order.yaml` in `services/order/infrastructure/src/contractTest/kotlin/com/ecommerce/order/OrderProviderPactTest.kt`, and for `getSimulatorRules`, `getPaymentAttempt`, `listPaymentAttemptsForOrder` in `services/payment/infrastructure/src/contractTest/kotlin/com/ecommerce/payment/PaymentProviderPactTest.kt`
- [X] T065 [P] [US4] Cucumber feature `acceptance/src/test/resources/features/checkout.feature` (approved order, declined payment, last unit race, retried checkout with the same key, item out of stock, price changed since cart viewed, provider unreachable) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/CheckoutSteps.kt`

### Implementation for User Story 4

- [X] T066 [P] [US4] Implement domain `Order` (id, orderNumber, accountId, lines, deliveryAddress snapshot, total, orderStatus, paymentStatus, cancellationReason?, statusHistory, paymentAttemptId?, createdAt), `OrderLine`, `StatusChange`, `IdempotencyRecord` (key, accountId, requestHash, orderId, responseSnapshot, expiresAt +24 h), enums `OrderStatus`, `PaymentStatus`, `CancellationReason` per data-model §3.4 in `services/order/domain/src/main/kotlin/com/ecommerce/order/domain/`
- [ ] T067 [P] [US4] Implement catalog domain `Reservation` (orderId, lines, state reserved|committed|released, expiresAt) and `InventoryLevel.reserve/commit/release` per data-model §3.2 in `services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/Reservation.kt`
- [X] T068 [P] [US4] Implement payment domain `PaymentAttempt` (id, orderId, amount, kind charge|refund, outcome approved|declined|pending, declineReason?, providerReference, idempotencyKey) and `RefundRecord`, plus `PaymentProviderPort` per data-model §3.5 in `services/payment/domain/src/main/kotlin/com/ecommerce/payment/domain/`
- [X] T069 [US4] Define order ports (`OrderRepository`, `IdempotencyStore`, `CartPort`, `StockReservationPort`, `PaymentPort`, `AddressPort`) and use case `PlaceOrder`: load cart by account, compare `cartRevision` (stale → PriceChanged with changed lines, no idempotency record), reserve stock synchronously (insufficient → InsufficientStock, no record), create order placed/pending, request charge, apply outcome (approved → commit + `approved`; declined → release + cancelled PAYMENT_FAILED; pending → keep pending), store idempotency record, emit events, in `services/order/application/src/main/kotlin/com/ecommerce/order/application/PlaceOrder.kt`
- [ ] T070 [US4] Implement catalog use cases `ReserveStock`, `CommitReservation`, `ReleaseReservation` (conditional update `available >= requested`, row-level guard) and internal endpoints `POST /internal/reservations`, `/internal/reservations/{id}/commit`, `/internal/reservations/{id}/release` (network-internal, not routed by gateway) in `services/catalog/application/src/main/kotlin/com/ecommerce/catalog/application/` and `services/catalog/infrastructure/src/main/kotlin/com/ecommerce/catalog/infrastructure/web/ReservationHandlers.kt`
- [X] T071 [US4] Implement payment use cases `AuthoriseCharge` (idempotent per order), `RecordRefund` and the `SimulatedPaymentProvider` adapter with the rule document, internal endpoint `POST /internal/charges`, and `getSimulatorRules`/attempt read handlers per `contracts/openapi/payment.yaml` in `services/payment/application/src/main/kotlin/com/ecommerce/payment/application/` and `services/payment/infrastructure/src/main/kotlin/com/ecommerce/payment/infrastructure/`
- [ ] T072 [US4] Implement Flyway `V2__order_schema.sql` (orders, order_lines, status_changes, idempotency_records with unique (account_id, key)), `V2__payment_schema.sql`, `V3__catalog_reservations.sql` and the R2DBC adapters in the respective `services/<ctx>/infrastructure/src/main/kotlin/com/ecommerce/<ctx>/infrastructure/persistence/`
- [X] T073 [US4] Implement order WebClient adapters `CartClient`, `StockReservationClient`, `PaymentClient`, `AddressClient` (DNS load-balanced, timeouts, retries only on connection errors) in `services/order/infrastructure/src/main/kotlin/com/ecommerce/order/infrastructure/clients/`
- [X] T074 [US4] Implement `placeOrder` handler with required `Idempotency-Key` (uuid) and `cartRevision`, responses 201/202/409 (`insufficient-stock` | `price-changed` with `changedLines`, `currentCartRevision`)/422 per `contracts/openapi/order.yaml` in `services/order/infrastructure/src/main/kotlin/com/ecommerce/order/infrastructure/web/OrderCommandHandlers.kt`
- [X] T075 [US4] Implement order outbox publication of `OrderPlaced`, `OrderPaid`, `OrderPaymentFailed` and consumers for `PaymentApproved`, `PaymentDeclined`, `PaymentPending`; payment publication of `PaymentApproved|Declined|Pending`; catalog consumers for `OrderPaid` (commit) and `OrderPaymentFailed` (release) per `contracts/asyncapi/events.yaml` in each `services/<ctx>/infrastructure/src/main/kotlin/com/ecommerce/<ctx>/infrastructure/messaging/`
- [X] T076 [US4] Implement the pending-payment expiry job (payment pending > 30 min → paymentStatus failed, orderStatus cancelled PAYMENT_EXPIRED, release reservation, publish `OrderCancelled`) in `services/order/infrastructure/src/main/kotlin/com/ecommerce/order/infrastructure/jobs/PaymentExpiryJob.kt`
- [X] T077 [US4] Configure the `checkout` rate-limit tier for `POST /api/v1/orders` and ensure `/internal/**` paths are unreachable through the gateway in `services/gateway/src/main/resources/application.yml`

**Checkpoint**: Full revenue journey (US1→US4) passes through the gateway; concurrency and idempotency tests green.

---

## Phase 7: User Story 5 - Track orders and history (Priority: P2)

**Goal**: Own order history and status, operator transitions with the preparing guard, shopper and operator cancellation with refund.

**Independent Test**: Place an order, operator moves it to preparing then shipped, shopper sees statuses with timestamps; shopper cancel after shipped is refused; cancel while placed returns stock and records a refund (spec US5 scenarios 1–5).

### Tests for User Story 5

- [X] T078 [P] [US5] Property tests for transition guards (`preparing` refused unless paymentStatus approved; shopper cancel only from placed; operator cancel from placed|preparing; illegal jumps refused and order unchanged) in `services/order/domain/src/test/kotlin/com/ecommerce/order/domain/OrderTransitionsSpec.kt`
- [X] T079 [P] [US5] Pact provider verification for `listOwnOrders`, `getOwnOrder`, `cancelOwnOrder`, `transitionOrderStatus` (cross-shopper → 404) against `contracts/openapi/order.yaml` in `services/order/infrastructure/src/contractTest/kotlin/com/ecommerce/order/OrderQueryProviderPactTest.kt`; message pacts order → payment for `OrderCancelled` (refund) and order → catalog for `OrderCancelled` (release)
- [X] T080 [P] [US5] Cucumber feature `acceptance/src/test/resources/features/order-tracking.feature` (history newest first and own only, operator ships, shopper cancels before preparing with refund, cancel after shipping refused, illegal transition refused) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/OrderTrackingSteps.kt`

### Implementation for User Story 5

- [X] T081 [P] [US5] Implement use cases `ListOwnOrders` (newest first, paged), `GetOwnOrder` (404 for others' orders), `CancelOwnOrder`, `TransitionOrderStatus` (operator; `cancelled` allowed from placed|preparing with reason OPERATOR) in `services/order/application/src/main/kotlin/com/ecommerce/order/application/`
- [X] T082 [US5] Implement handlers `listOwnOrders`, `getOwnOrder`, `cancelOwnOrder` (409 when not placed), `transitionOrderStatus` (operator role) per `contracts/openapi/order.yaml` in `services/order/infrastructure/src/main/kotlin/com/ecommerce/order/infrastructure/web/OrderQueryHandlers.kt`
- [X] T083 [US5] Publish `OrderPreparing`, `OrderShipped`, `OrderDelivered`, `OrderCancelled` (reason SHOPPER_REQUEST|OPERATOR, orderStatus, paymentStatus) and consume `RefundRecorded`; implement payment consumer for `OrderCancelled` with paymentStatus approved → `RecordRefund` → `RefundRecorded`; catalog consumer for `OrderCancelled` → release, in the respective `services/<ctx>/infrastructure/src/main/kotlin/com/ecommerce/<ctx>/infrastructure/messaging/`
- [X] T084 [US5] Add `listRefundsForOrder` and `getRefund` handlers per `contracts/openapi/payment.yaml` in `services/payment/infrastructure/src/main/kotlin/com/ecommerce/payment/infrastructure/web/PaymentQueryHandlers.kt`

**Checkpoint**: Order lifecycle fully observable and controllable; refunds recorded on cancellation.

---

## Phase 8: User Story 6 - Receive notifications for account and order events (Priority: P2)

**Goal**: Templated email (and opt-in SMS) for registration, reset, order paid, payment failed, shipped, delivered; retries, dedupe, operator visibility, preferences.

**Independent Test**: Trigger `OrderPaid` with Mailpit as sink → one message with order details within 30 s; make the sink fail → retries then `failed` visible to operators; duplicate event → one message (spec US6 scenarios 1–5).

### Tests for User Story 6

- [X] T085 [P] [US6] Property tests for `Notification` (delivery status queued→sent|failed, retry schedule 5 attempts exponential from 30 s capped at 10 min, dedupe by eventId, channel selection by preferences and verified phone) in `services/notification/domain/src/test/kotlin/com/ecommerce/notification/domain/NotificationSpec.kt`
- [X] T086 [P] [US6] Message pact consumer tests for every consumed event (`AccountRegistered`, `AccountVerified`, `AccountDeleted`, `PasswordResetRequested`, `OrderPaid`, `OrderPaymentFailed`, `OrderShipped`, `OrderDelivered`) in `services/notification/infrastructure/src/contractTest/kotlin/com/ecommerce/notification/EventConsumerPactTest.kt`
- [X] T087 [P] [US6] Integration test with Testcontainers Kafka + Mailpit container: event → email within 30 s, failing SMTP → retries → failed, duplicate eventId → single send in `services/notification/infrastructure/src/integrationTest/kotlin/com/ecommerce/notification/DeliveryIT.kt`
- [X] T088 [P] [US6] Pact provider verification for `listOwnNotifications`, `listFailedNotifications`, `retryFailedNotification` against `contracts/openapi/notification.yaml` in `services/notification/infrastructure/src/contractTest/kotlin/com/ecommerce/notification/NotificationProviderPactTest.kt`
- [X] T089 [P] [US6] Cucumber feature `acceptance/src/test/resources/features/notifications.feature` (order confirmation email, SMS when opted in, failed channel visible to operator and retried, preference change honoured) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/NotificationSteps.kt`

### Implementation for User Story 6

- [X] T090 [P] [US6] Implement domain `Notification` (id, accountId, recipient snapshot, channel email|sms, type, content, status, attempts), `DeliveryAttempt`, `RecipientPreference` read model and templates per type per data-model §3.6 in `services/notification/domain/src/main/kotlin/com/ecommerce/notification/domain/`
- [X] T091 [P] [US6] Define ports `EmailSenderPort`, `SmsSenderPort`, `NotificationRepository`, `PreferenceReadModel` and use cases `ProduceNotificationFromEvent`, `AttemptDelivery`, `RetryFailed`, `ListOwn`, `ListFailed` in `services/notification/application/src/main/kotlin/com/ecommerce/notification/application/`
- [X] T092 [US6] Implement Flyway `V2__notification_schema.sql`, R2DBC repository, SMTP `EmailSenderAdapter` (Mailpit locally), `SimulatedSmsSender`, and the retry scheduler in `services/notification/infrastructure/src/main/kotlin/com/ecommerce/notification/infrastructure/`
- [X] T093 [US6] Implement idempotent Kafka consumers for the eight consumed events (preference snapshot from identity events; tokens used to build links and never logged) and publication of `NotificationSent`/`NotificationFailed` in `services/notification/infrastructure/src/main/kotlin/com/ecommerce/notification/infrastructure/messaging/`
- [X] T094 [US6] Implement handlers `listOwnNotifications`, `listFailedNotifications` (operator), `retryFailedNotification` (operator) per `contracts/openapi/notification.yaml` in `services/notification/infrastructure/src/main/kotlin/com/ecommerce/notification/infrastructure/web/NotificationHandlers.kt`

**Checkpoint**: Every account and order event yields exactly one message attempt within 30 s (SC-005).

---

## Phase 9: User Story 7 - Operate the catalogue and inventory (Priority: P2)

**Goal**: Operator-only product, category, image, price and stock management with attribution and audit.

**Independent Test**: Operator creates a product with stock 5 in a new category and a shopper can buy it; a shopper account attempting the same gets 403 and the attempt is logged (spec US7 scenarios 1–4).

### Tests for User Story 7

- [ ] T095 [P] [US7] Property tests for `StockAdjustment` (reason mandatory, negative adjustment cannot take available below reserved, audit fields who/when/why) and `withdraw` (not visible, not addable, open orders unaffected) in `services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/CatalogOperationsSpec.kt`
- [ ] T096 [P] [US7] Pact provider verification for `createProduct`, `updateProduct`, `withdrawProduct`, `adjustStock`, `addProductImage`, `createCategory`, `updateCategory` against `contracts/openapi/catalog.yaml` in `services/catalog/infrastructure/src/contractTest/kotlin/com/ecommerce/catalog/CatalogAdminProviderPactTest.kt`
- [X] T097 [P] [US7] Cucumber feature `acceptance/src/test/resources/features/catalogue-operations.feature` (create product visible immediately, stock adjustment with reason, withdraw with open orders, shopper refused) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/CatalogueOperationsSteps.kt`

### Implementation for User Story 7

- [ ] T098 [P] [US7] Implement domain `StockAdjustment` (productId, delta, reason, actorId, at) and `Product.withdraw()`/`update()` rules per data-model §3.2 in `services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/StockAdjustment.kt`
- [ ] T099 [US7] Implement operator use cases `CreateProduct`, `UpdateProduct`, `WithdrawProduct`, `AdjustStock`, `AddProductImage`, `CreateCategory`, `UpdateCategory` with role checks in the application layer in `services/catalog/application/src/main/kotlin/com/ecommerce/catalog/application/admin/`
- [ ] T100 [US7] Implement Flyway `V4__catalog_audit.sql` (stock_adjustments), persistence and handlers for the seven operator operations with 403 for shoppers and audit logging of refused attempts per `contracts/openapi/catalog.yaml` in `services/catalog/infrastructure/src/main/kotlin/com/ecommerce/catalog/infrastructure/web/CatalogAdminHandlers.kt`

**Checkpoint**: Catalogue is operable without seed data; authorisation sweep (SC-010) passes for catalogue operations.

---

## Phase 10: User Story 8 - Run and observe the whole platform (Priority: P3)

**Goal**: One-command start, single entry point, correlated central logs and traces, DNS discovery with instance churn, health and metrics.

**Independent Test**: Start with the documented command, run the journey, find one correlation id across ≥ 3 services in Grafana, scale catalog to 2 and stop one while browsing continues (spec US8 scenarios 1–5, quickstart §Observability/§Resilience).

### Tests for User Story 8

- [ ] T101 [P] [US8] Compose smoke test script `platform/compose/scripts/smoke.sh` (start `core`+`observability`, wait for all health checks within 5 min, call the gateway, assert services unreachable on host ports except gateway/Grafana/Mailpit)
- [ ] T102 [P] [US8] Resilience test script `platform/compose/scripts/resilience.sh` (`--scale catalog=2`, continuous browse loop, stop one instance, assert zero non-2xx after in-flight requests)
- [X] T103 [P] [US8] Cucumber feature `acceptance/src/test/resources/features/platform-observability.feature` (request traceable across services by correlation id via Loki API, health and metrics exposed by every service) and steps in `acceptance/src/test/kotlin/com/ecommerce/acceptance/steps/ObservabilitySteps.kt`

### Implementation for User Story 8

- [ ] T104 [P] [US8] Configure Spring Cloud LoadBalancer with DNS-based discovery and `networkaddress.cache.ttl=5` in every service's `application.yml` and `JAVA_TOOL_OPTIONS` in `platform/docker/Dockerfile.service`; set retry-on-connect-error for WebClient adapters in `libs/platform-core/src/main/kotlin/com/ecommerce/platform/http/WebClientDefaults.kt`
- [ ] T105 [P] [US8] Finalise `platform/observability/` configs: Loki, Tempo, Prometheus scrape of `/actuator/prometheus` for all services, Grafana dashboards for request rate/error/latency per service and the correlation search, and link logs↔traces via `traceId`
- [ ] T106 [P] [US8] Enforce network isolation in `platform/compose/docker-compose.yml`: services and databases on an internal network, only `gateway:8080`, `grafana:3000`, `mailpit:8025` published to the host
- [X] T107 [US8] Write `docs/running-locally.md` (one-command start, profiles, seed, URLs, scaling, teardown) referencing `specs/004-ecommerce-platform-mvp/quickstart.md`

**Checkpoint**: Quickstart §Start, §Observability and §Resilience steps pass on a clean machine.

---

## Phase 11: User Story 9 - Build, test and release each service independently (Priority: P3)

**Goal**: Path-filtered per-service pipelines on the containerised self-hosted runner, Pact verification gating, images pushed to the private registry.

**Independent Test**: Change one line in the cart service; only the cart workflow runs, publishes only the cart image, and Pact `can-i-deploy` passes (spec US9 scenarios 1–3).

### Tests for User Story 9

- [X] T108 [P] [US9] Workflow dry-run test using `act` or a documented manual check in `platform/ci-runner/README.md` verifying path filters trigger only the touched service workflow

### Implementation for User Story 9

- [X] T109 [P] [US9] Create `platform/ci-runner/docker-compose.yml` with an ephemeral `actions/runner` (or `myoung34/github-runner`) container with Docker socket mount, `registry:2` with basic auth + TLS, and the Pact Broker; document registration, labels and the public-repo safeguards (no fork PR execution, approval for outside collaborators, SHA-pinned actions) in `platform/ci-runner/README.md`
- [X] T110 [P] [US9] Create the reusable workflow `.github/workflows/service-ci.yml` (inputs: service name; steps: checkout, JDK 25, Gradle quiet `check` for the service modules, `pitest`, Pact publish + `can-i-deploy`, Docker build from the service Dockerfile, push `<registry>/<service>:<sha>` and `:<branch>`) targeting `runs-on: [self-hosted, ecommerce]`
- [X] T111 [P] [US9] Create per-service caller workflows `.github/workflows/<ctx>.yml` for gateway, identity, catalog, cart, order, payment, notification with `paths:` filters on `services/<ctx>/**`, `libs/**`, `build-logic/**`, `gradle/**`, `contracts/**`
- [X] T112 [P] [US9] Create `.github/workflows/platform.yml` (compose config validation, acceptance suite against a `core` stack on the runner, observability config lint) with filters on `platform/**` and `acceptance/**`
- [X] T113 [US9] Register the required status checks (`service-ci / <ctx>`, `platform`) in the branch protection configured by feature 003 and document the merge policy in `docs/ci-cd.md`

**Checkpoint**: A single-service change builds, tests and publishes only that service within 15 min (SC-009).

---

## Phase 12: Polish & Cross-Cutting Concerns

**Purpose**: Hardening, performance evidence and documentation across all stories.

- [X] T114 [P] Authorisation sweep test: every operator operation as shopper → 403 and every protected operation anonymously → 401 across all six contracts in `acceptance/src/test/resources/features/authorisation-sweep.feature` (SC-010)
- [ ] T115 [P] Load test with k6 or Gatling: 1,000 concurrent browsing shoppers and 100 concurrent checkouts, p95 catalogue < 1 s at 10,000 products, in `platform/perf/browse-and-checkout.js` with a seeded 10k-product dataset generator `platform/perf/seed-10k.sql` (SC-002, SC-003)
- [X] T116 [P] Dependency vulnerability scanning and image scanning steps added to `.github/workflows/service-ci.yml`; SBOM generation task in `build-logic`
- [X] T117 [P] PII audit: assert no `Email`, `PhoneNumber`, `PostalAddress` or token values reach logs (log-capture test) in `libs/platform-core/src/test/kotlin/com/ecommerce/platform/observability/PiiMaskingSpec.kt`
- [X] T118 [P] Mutation thresholds: enable Pitest with the Kotlin plugin (Arcmutate licence per research §14, or documented exclusions fallback) at 80 % for every `domain` and `application` module in `build-logic/src/main/kotlin/pitest.gradle.kts`
- [X] T119 [P] Write `docs/architecture.md` (bounded contexts, layers, event flows, status model) and `docs/adr/0001-two-status-order-model.md`, `docs/adr/0002-synchronous-stock-reservation.md`, `docs/adr/0003-cart-revision-checkout.md`
- [X] T120 Propose the constitution PATCH amendment ("Spring Boot, latest GA major") via `/speckit-constitution` and record the Flyway blocking-at-boot exception in `docs/architecture.md`
- [ ] T121 Run the full `specs/004-ecommerce-platform-mvp/quickstart.md` on a clean machine and record results in `docs/validation/2026-10-quickstart-run.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: depends on features 001–003 being implemented; otherwise none
- **Foundational (Phase 2)**: depends on Phase 1 — BLOCKS all user stories
- **User Stories (Phases 3–11)**: all depend on Phase 2; order of value: US1 → US2 → US3 → US4 (MVP revenue journey), then US5, US6, US7, then US8, US9
- **Polish (Phase 12)**: depends on the stories it hardens; T115 needs US1–US4, T114 needs all contracts implemented

### User Story Dependencies

- **US1 (catalogue)**: none beyond Phase 2
- **US2 (cart)**: reads prices from US1's catalog API; the merge scenario needs US3 sign-in
- **US3 (identity)**: none beyond Phase 2; unlocks every protected operation
- **US4 (checkout)**: needs US1 (products/stock), US2 (cart with revision), US3 (sessions, addresses)
- **US5 (tracking)**: needs US4 orders
- **US6 (notifications)**: consumes US3 and US4/US5 events; testable with produced events alone
- **US7 (operator catalogue)**: needs US1 model and US3 operator role
- **US8 (platform run/observe)**: needs the Compose stack from Phase 2 and any two services
- **US9 (CI/CD)**: needs the build from feature 002 and at least one service to pipeline

### Within Each User Story

- Tests (property, integration, Pact, Cucumber) are written first and fail before implementation
- Domain → application (ports, use cases) → infrastructure (persistence, web, messaging) → gateway wiring
- Story complete and its feature file green before moving on

### Parallel Opportunities

- Phase 1: T002–T009 in parallel after T001
- Phase 2: T010–T019 in parallel; T022–T026 in parallel after T020
- Each story: all test tasks in parallel; domain tasks for different services in parallel
- After Phase 2, US1, US3 and US8 can proceed concurrently by different developers; US2 and US7 after US1's domain; US4 after US1–US3

---

## Parallel Example: User Story 4

```bash
# Tests first, in parallel:
Task: "Property tests for Order aggregate in services/order/domain/src/test/kotlin/com/ecommerce/order/domain/OrderSpec.kt"
Task: "Property tests for catalog Reservation in services/catalog/domain/src/test/kotlin/com/ecommerce/catalog/domain/ReservationSpec.kt"
Task: "Property tests for SimulatedPaymentProvider rules in services/payment/infrastructure/src/test/kotlin/.../SimulatedPaymentProviderSpec.kt"
Task: "Pact consumer tests order → cart/catalog/payment in services/order/infrastructure/src/contractTest/kotlin/com/ecommerce/order/"

# Then domain models for three services in parallel:
Task: "Implement Order aggregate in services/order/domain/src/main/kotlin/com/ecommerce/order/domain/"
Task: "Implement catalog Reservation in services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/Reservation.kt"
Task: "Implement PaymentAttempt and PaymentProviderPort in services/payment/domain/src/main/kotlin/com/ecommerce/payment/domain/"
```

---

## Implementation Strategy

### MVP First (US1 → US4)

1. Complete Phase 1 and Phase 2
2. Deliver US1 (browse) and validate with its feature file — first demonstrable increment
3. Add US3 (identity) then US2 (cart with merge), then US4 (checkout) — the revenue journey
4. **STOP and VALIDATE**: run quickstart steps 1–11 and the concurrency and idempotency tests

### Incremental Delivery

1. US5 tracking → US6 notifications → US7 operator tooling, each validated by its feature file
2. US8 platform observability and resilience checks, US9 per-service pipelines
3. Phase 12 hardening, performance evidence and documentation

### Parallel Team Strategy

- Developer A: identity (US3) then order (US4, US5)
- Developer B: catalog (US1, US7) then payment (US4)
- Developer C: cart (US2) then notification (US6)
- Platform engineer: Phase 2 messaging/observability libraries, then US8 and US9

---

## Notes

- Every task names its module and file path; constraints are quoted from data-model.md so they are not left to implementation-time discretion
- The harness hooks from feature 001 run lint and targeted tests on every edit and the full gate at task end; keep Gradle output quiet
- Internal endpoints (`/internal/**`) are never routed by the gateway; Pact covers them as order → catalog/payment consumer contracts
- Commit after each task or logical group; stop at any checkpoint to validate the story independently

# Feature Specification: E-Commerce Platform MVP

**Feature Branch**: `004-ecommerce-platform-mvp`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description (recorded verbatim below; product and tool names in it are the
requester's suggestions, not requirements of this specification):

> Build a scalable e-commerce platform using microservices architecture and Docker. The platform
> will handle various aspects of an online store, such as product catalog management, user
> authentication, shopping cart, payment processing, and order management. Each of these features
> will be implemented as separate microservices, allowing for independent development, deployment,
> and scaling.
> Core Microservices: User Service (registration, authentication, profile management); Product
> Catalog Service (listings, categories, inventory); Shopping Cart Service (add/remove items,
> update quantities); Order Service (place orders, track status, order history); Payment Service
> (payment processing, integrating with external gateways e.g. Stripe, PayPal); Notification
> Service (email and SMS for order confirmation, shipping updates; e.g. Twilio, SendGrid).
> Additional Components: API Gateway (Kong, Traefik or NGINX); Service Discovery (Consul or
> Eureka); Centralized Logging (ELK stack); Docker & Docker Compose; CI/CD Pipeline (Jenkins,
> GitLab CI or GitHub Actions).
> Steps to Get Started: set up Docker and Docker Compose; develop microservices starting with a
> simple MVP per service; integrate services via REST or gRPC behind an API Gateway; implement
> service discovery; set up monitoring and logging (Prometheus, Grafana, ELK); deploy with Docker
> Swarm or Kubernetes with auto-scaling and load balancing; CI/CD integration.

**Scope decisions confirmed with the requester on 2026-10-02**: one umbrella MVP across all six
bounded contexts; API-first (the web storefront is a separate later feature); payments through a
simulated provider only; an account is required to place an order, while browsing and the cart
are available anonymously.

## Clarifications

### Session 2026-10-02

- Q: Which single order lifecycle should the spec state so that User Story 5 and FR-015 no longer
  disagree about whether payment states are part of it? → A: Two separate statuses. Order status:
  `placed` → `preparing` → `shipped` → `delivered`, with `cancelled` reachable from `placed` or
  `preparing`. Payment status: `pending` → `approved` or `failed`. An order can move to
  `preparing` only when its payment is `approved`; a failed or expired payment cancels the order.
- Q: When should stock be reserved for an order, and how should that reservation be stated as a
  requirement? → A: Reserve at checkout through a synchronous request from the order context to
  the catalogue context, before payment is attempted; commit on payment approval; release on
  payment failure, cancellation or expiry of a pending payment. Insufficient stock refuses the
  checkout immediately, naming the unavailable lines.
- Q: Which price does an order use when a product's price changed after it was added to the cart
  but the shopper has not refreshed the cart before confirming checkout? → A: Explicit
  acknowledgement. The checkout request carries the cart revision the shopper last saw; if any
  line's price changed since that revision, checkout is refused listing the changed lines and
  prices, and the shopper confirms by resubmitting with the new revision. Orders always freeze
  the current catalogue price at confirmation.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse the catalogue without an account (Priority: P1)

A shopper arriving at the store can list products, browse by category, search by name and see
each product's price, description, images and whether it is in stock, all without registering.

**Why this priority**: Discovery is the entry point of every purchase and the most read-heavy part
of the platform. Nothing else is reachable for a shopper if browsing does not work.

**Independent Test**: With a seeded catalogue, list products, open a category, search for a term,
and open one product; confirm the expected items, prices and availability are returned to an
anonymous caller.

**Acceptance Scenarios**:

1. **Given** a catalogue with products in several categories, **When** an anonymous shopper lists
   products in a category, **Then** only that category's active products are returned, paged, with
   name, price, primary image and availability.
2. **Given** a product whose stock is zero, **When** a shopper views it, **Then** it is shown as
   out of stock and cannot be added to a cart.
3. **Given** a search term matching several products, **When** the shopper searches, **Then**
   matching products are returned ranked by relevance and products withdrawn by an operator are
   never returned.
4. **Given** a product identifier that does not exist, **When** it is requested, **Then** the
   shopper receives a clear not-found response.

---

### User Story 2 - Build a cart anonymously and keep it after signing in (Priority: P1)

A shopper adds products to a cart before signing in, changes quantities, removes items and sees
the running total. When they later sign in, the anonymous cart is merged into their account cart.

**Why this priority**: The cart is where purchase intent is captured; losing it at sign-in is a
leading cause of abandoned purchases.

**Independent Test**: Add two products anonymously, sign in to an account that already has one
item in its cart, and confirm the account cart now holds all three with correct quantities and
total.

**Acceptance Scenarios**:

1. **Given** an anonymous shopper, **When** they add a product with quantity 2, **Then** the cart
   contains that line, the unit price at time of adding and a correct total.
2. **Given** a cart line with quantity 2, **When** the shopper sets quantity to 0 or removes the
   line, **Then** the line disappears and the total is recalculated.
3. **Given** an anonymous cart and an account cart containing the same product, **When** the
   shopper signs in, **Then** the quantities are summed up to the available stock and the shopper
   is told if any quantity was capped.
4. **Given** a product whose price changed after it was added, **When** the cart is viewed,
   **Then** the current price is shown and the shopper is informed of the change.
5. **Given** a shopper adds more units than are in stock, **When** the add is attempted, **Then**
   the quantity is refused with the available quantity stated.

---

### User Story 3 - Register, sign in and manage the account (Priority: P1)

A shopper creates an account with email and password, verifies the email, signs in, signs out,
resets a forgotten password and maintains profile details and delivery addresses. Signing in is
required before placing an order.

**Why this priority**: Orders, history and notifications all hang off an identity; the
constitution requires deny-by-default access, so identity must exist before checkout can.

**Independent Test**: Register a new account, confirm the verification message is produced, sign
in, add an address, sign out, sign in again and confirm the address is still there; attempt
checkout while signed out and confirm it is refused.

**Acceptance Scenarios**:

1. **Given** a valid email and a password meeting the policy, **When** a shopper registers,
   **Then** the account is created in an unverified state and a verification notification is sent.
2. **Given** an email already registered, **When** registration is attempted again, **Then** it
   is refused without revealing whether the email exists to an unauthenticated caller beyond the
   generic message.
3. **Given** a verified account, **When** the shopper signs in with correct credentials, **Then**
   they receive a session they can use for protected actions; **When** credentials are wrong five
   times in a row, **Then** further attempts are throttled.
4. **Given** a signed-in shopper, **When** they add, edit or remove a delivery address, **Then**
   the change is persisted and visible on next sign-in.
5. **Given** a shopper who forgot their password, **When** they request a reset, **Then** a
   single-use, time-limited reset is sent and the old password stops working once a new one is set.
6. **Given** a signed-out shopper with a cart, **When** they attempt to place an order, **Then**
   they are asked to sign in or register first and the cart is preserved.

---

### User Story 4 - Place an order and pay (Priority: P1)

A signed-in shopper reviews the cart, chooses a delivery address, confirms the order and pays. The
platform reserves stock, charges through the simulated payment provider, and either confirms the
order or explains why it failed. The cart is emptied only on success.

**Why this priority**: This is the revenue-generating journey and the most sensitive one: money,
stock and personal data meet here. The constitution's idempotency and security rules apply.

**Independent Test**: With a cart of two in-stock items and a saved address, place an order using
a payment method the simulator approves; confirm the order is created as paid, stock decreased by
the ordered quantities, the cart is empty and a confirmation notification was produced. Repeat with
a method the simulator declines and confirm no stock change, order marked payment-failed, cart
intact.

**Acceptance Scenarios**:

1. **Given** a signed-in shopper with a non-empty cart and a saved address, **When** they confirm
   checkout with an approved payment method, **Then** an order is created with lines, prices and
   totals frozen at that moment, stock is reserved then committed, the payment is recorded as
   approved, the order is `placed` with payment status `approved`, and the cart is emptied.
2. **Given** the same cart, **When** the simulated provider declines the payment, **Then** the
   order is recorded with payment status `failed` and order status `cancelled` (reason "payment
   failed"), reserved stock is released, the cart is kept and the shopper sees the decline reason
   category.
3. **Given** two shoppers with the last unit of a product in their carts, **When** both check out
   at the same time, **Then** exactly one order succeeds and the other is refused for insufficient
   stock; stock never goes negative.
4. **Given** a checkout request that is retried because the shopper did not receive a response,
   **When** the same request is submitted again with the same idempotency key, **Then** exactly one
   order and one charge exist.
5. **Given** a cart item that went out of stock since it was added, **When** checkout is
   attempted, **Then** the shopper is told which item is unavailable and no order is created.
6. **Given** a cart line whose price changed after the shopper last viewed the cart, **When**
   checkout is submitted with the earlier cart revision, **Then** the checkout is refused listing
   the changed lines with old and new prices and no order is created; **When** it is resubmitted
   with the current revision, **Then** the order is placed at the current prices.
7. **Given** an order total, **When** the simulated provider is unreachable, **Then** the order stays
   `placed` with payment status `pending`, no stock is committed, and the shopper is told to retry;
   a later retry approves or fails the payment without duplicating the order, and a payment still
   `pending` after 30 minutes cancels the order (reason "payment expired") and releases stock.

---

### User Story 5 - Track orders and history (Priority: P2)

A signed-in shopper sees their order history and the current status of each order as it moves
through its lifecycle: `placed`, `preparing`, `shipped`, `delivered` or `cancelled`, with the
payment status (`pending`, `approved`, `failed`) shown alongside. Operators advance order statuses;
shoppers may cancel before preparation starts.

**Why this priority**: Post-purchase visibility drives trust and reduces support load, but it
requires orders to exist first.

**Independent Test**: Place an order, have an operator advance it to "shipped", and confirm the
shopper sees the updated status and timestamp; attempt a shopper cancellation after shipping and
confirm it is refused.

**Acceptance Scenarios**:

1. **Given** a shopper with three orders, **When** they view history, **Then** all three appear,
   newest first, each with order status, payment status, total and date, and no other shopper's orders are visible.
2. **Given** a `preparing` order with approved payment, **When** an operator marks it shipped, **Then** the status changes,
   the change is time-stamped and a shipping notification is produced.
3. **Given** a `placed` order with approved payment, **When** the shopper cancels, **Then** the status
   becomes cancelled, stock is returned and a refund is recorded with the simulated provider.
4. **Given** an order already shipped, **When** the shopper attempts to cancel, **Then** the
   cancellation is refused with the reason.
5. **Given** any order, **When** an order-status transition not allowed by the lifecycle is
   attempted, or a move to `preparing` is attempted while payment is not `approved`, **Then** it is
   refused and the order is unchanged.

---

### User Story 6 - Receive notifications for account and order events (Priority: P2)

Shoppers receive messages for registration verification, password reset, order confirmation,
payment failure, shipping and delivery. Messages go to email by default and to SMS when the
shopper has opted in with a verified number. Failed deliveries are retried and visible to
operators.

**Why this priority**: Notifications close the loop on every other story but are not required to
complete a purchase.

**Independent Test**: Trigger an order confirmation with the outbound channel pointed at a test
sink; confirm one message with the order details is produced within the time budget. Make the
channel fail; confirm the message is retried and appears on the operator's failed-delivery view.

**Acceptance Scenarios**:

1. **Given** an order becomes paid, **When** the notification is produced, **Then** it contains
   the order number, lines, total and delivery address and is sent to the account's email.
2. **Given** a shopper opted in to SMS with a verified number, **When** their order ships,
   **Then** both an email and an SMS are produced.
3. **Given** the outbound channel fails, **When** delivery is attempted, **Then** it is retried
   with increasing delay up to a limit and then recorded as failed and visible to operators.
4. **Given** the same order event is received twice, **When** notifications are processed,
   **Then** only one message is sent.
5. **Given** a shopper changes notification preferences, **When** the next event occurs, **Then**
   the new preferences are honoured.

---

### User Story 7 - Operate the catalogue and inventory (Priority: P2)

A store operator creates and updates products and categories, uploads images, sets prices,
adjusts stock levels, and withdraws products from sale. Only accounts with the operator role can
do this; every change is attributed and auditable.

**Why this priority**: The storefront needs a maintained catalogue, but seeded data suffices to
prove the shopper journey, so operator tooling follows the P1 stories.

**Independent Test**: As an operator, create a product in a new category with stock 5, confirm a
shopper can see and buy it; as a shopper account, attempt the same creation and confirm it is
refused.

**Acceptance Scenarios**:

1. **Given** an operator, **When** they create a product with name, description, price, category
   and stock, **Then** it becomes visible to shoppers immediately with the given availability.
2. **Given** an operator, **When** they adjust stock by a positive or negative amount with a
   reason, **Then** the new level is applied and the adjustment is recorded with who, when and why.
3. **Given** a product with open orders, **When** an operator withdraws it from sale, **Then** it
   disappears from browsing and carts cannot add it, while existing orders are unaffected.
4. **Given** a shopper account, **When** it attempts any catalogue change, **Then** the request is
   refused and the attempt is logged.

---

### User Story 8 - Run and observe the whole platform (Priority: P3)

A platform engineer starts the entire platform locally with one command, reaches every capability
through a single public entry point, sees logs from all services in one place correlated by a
single request identifier, and can add or remove instances of any service while traffic keeps
flowing.

**Why this priority**: These are the enabling qualities the requester asked for; they make the
system operable and scalable but deliver no shopper value on their own.

**Independent Test**: Start the platform with the documented command, perform the full shopper
journey through the public entry point, pick one request's identifier and find its log entries
from at least three services in the central log view; stop one instance of the catalogue service
while browsing continues without errors.

**Acceptance Scenarios**:

1. **Given** a developer machine with the supported container runtime, **When** the start command
   runs, **Then** all services and their dependencies start, pass health checks and the public
   entry point answers within the time budget.
2. **Given** the running platform, **When** a client calls any capability, **Then** it does so
   only through the single public entry point; services are not reachable directly from outside.
3. **Given** a request that crosses several services, **When** its identifier is searched in the
   central log view, **Then** every participating service's entries appear with that identifier.
4. **Given** two instances of a service, **When** one is stopped, **Then** requests continue to be
   served by the other with no client-visible errors, and a new instance is discovered without
   configuration changes.
5. **Given** any service, **When** it is queried for health and metrics, **Then** it reports
   readiness, liveness and the request, error and latency metrics the constitution requires.

---

### User Story 9 - Build, test and release each service independently (Priority: P3)

Every change to a service runs that service's full quality gate and produces a deployable
container image for it, without rebuilding or redeploying unrelated services.

**Why this priority**: Independent deployability is the point of the microservice split, but it
depends on the services and the build existing first.

**Independent Test**: Change one line in the cart service, confirm the pipeline builds, tests and
publishes only the cart service image, and that the contract tests between cart and its
consumers still pass.

**Acceptance Scenarios**:

1. **Given** a change confined to one service, **When** the pipeline runs, **Then** only that
   service's gate and image publication execute, and the result is reported on the change.
2. **Given** a change that breaks a published contract between two services, **When** the
   pipeline runs, **Then** it fails naming the contract and the consumer affected.
3. **Given** a published image, **When** it is started with the platform's configuration,
   **Then** it registers itself and begins serving without manual steps.

---

### Edge Cases

- A product sells out between adding to cart and checkout: the shopper is told which line is
  unavailable; no partial order is created unless the shopper removes the line.
- Two shoppers race for the last unit: exactly one succeeds; stock never goes negative.
- A checkout is retried after a timeout: the same idempotency key yields the same single order.
- The simulated payment provider is unreachable: the order stays `placed` with payment status
  `pending` and no committed stock; it can be retried, or after 30 minutes it is cancelled with
  reason "payment expired" and stock is released.
- A shopper signs in on two devices with two anonymous carts: both merge into the account cart
  with quantities capped at stock.
- A price changes after an item is added to the cart: the cart shows the current price and flags
  the change; checkout with a stale cart revision is refused until the shopper resubmits with the
  current revision, and the order then freezes the current price.
- The outbound notification channel is down: messages are retried with increasing delay and then
  surfaced to operators; the order itself is unaffected.
- A service instance disappears mid-request: the client receives an error for that request only,
  later requests are routed to healthy instances.
- A request arrives for an unknown capability at the public entry point: a clear not-found
  response is returned and nothing is forwarded.
- An account with open orders is deleted: the account is anonymised, open orders are kept for
  fulfilment and financial record under a pseudonym, and notifications stop.
- A client sends a correlation identifier that is malformed or spoofed: it is replaced by a new
  one and the original value is recorded for investigation.

## Requirements *(mandatory)*

### Functional Requirements

Catalogue and inventory

- **FR-001**: Shoppers MUST be able to list, page, filter by category and search active products
  without an account, seeing name, description, images, price and availability.
- **FR-002**: Operators MUST be able to create, update and withdraw products and categories, set
  prices and adjust stock with an attributed, time-stamped reason.
- **FR-003**: Withdrawn and zero-stock products MUST NOT be addable to carts; withdrawn products
  MUST NOT appear in browsing or search.

Identity and accounts

- **FR-004**: Shoppers MUST be able to register with email and password, verify the email, sign
  in, sign out, reset a forgotten password and manage profile and delivery addresses.
- **FR-005**: Every capability except browsing the catalogue and manipulating an anonymous cart
  MUST require an authenticated session, and operator capabilities MUST additionally require the
  operator role; access MUST be denied by default.
- **FR-006**: Repeated failed sign-ins MUST be throttled, and reset links MUST be single-use and
  time-limited.
- **FR-007**: Personal data MUST be limited to what the journeys need and MUST be anonymised on
  account deletion while preserving order and financial records.

Cart

- **FR-008**: Anonymous and signed-in shoppers MUST be able to add, update quantity of, and remove
  cart lines and see the running total; quantities MUST be capped at available stock.
- **FR-009**: On sign-in, an anonymous cart MUST be merged into the account cart, summing
  quantities up to stock and informing the shopper of any capping.
- **FR-010**: The cart MUST show current prices, flag lines whose price changed since they were
  added, and expose a cart revision that changes whenever lines, quantities or prices change.

Orders and payments

- **FR-011**: A signed-in shopper MUST be able to place an order from the cart with a chosen
  delivery address and the cart revision they last viewed. If any line's price changed since that
  revision, the checkout MUST be refused listing the changed lines with old and new prices and no
  order MUST be created. Order lines, prices and totals MUST be frozen at the current catalogue
  prices at confirmation.
- **FR-012**: Stock MUST be reserved at checkout, before any payment attempt, through a
  synchronous request from the order context to the catalogue context that either reserves every
  line or refuses the checkout naming the unavailable lines. A reservation MUST be committed when
  payment is approved and released when payment fails, the order is cancelled, or a pending
  payment expires. Stock MUST never become negative under concurrent checkouts.
- **FR-013**: Checkout MUST be idempotent: the same request submitted more than once MUST
  produce exactly one order and one payment attempt.
- **FR-014**: Payments MUST be processed through a payment provider abstraction; the MVP MUST
  ship with a simulated provider that approves or declines deterministically by documented rules
  and can be swapped for a real provider without changing the order journey.
- **FR-015**: Orders MUST carry two statuses. Order status MUST follow `placed` → `preparing` →
  `shipped` → `delivered`, with `cancelled` reachable only from `placed` or `preparing`; payment
  status MUST follow `pending` → `approved` or `failed`. An order MUST move to `preparing` only
  while its payment is `approved`; a `failed` payment or a payment `pending` for more than
  30 minutes MUST cancel the order (reasons "payment failed" / "payment expired") and release
  stock. Every cancellation MUST record a reason (shopper request, operator, payment failed,
  payment expired). Transitions outside these rules MUST be refused.
- **FR-016**: Shoppers MUST be able to view their own order history and status, and MUST NOT be
  able to view others'; shoppers MUST be able to cancel an order before preparation begins, with
  stock returned and a refund recorded.
- **FR-017**: Operators MUST be able to advance order status through the lifecycle and to cancel
  an order while it is `placed` or `preparing`.

Notifications

- **FR-018**: The platform MUST produce notifications for registration verification, password
  reset, order confirmation, payment failure, shipping and delivery, by email by default and by
  SMS when the shopper has opted in with a verified number.
- **FR-019**: Notification delivery MUST be retried with increasing delay up to a limit; failures
  MUST be visible to operators; duplicate events MUST NOT produce duplicate messages.
- **FR-020**: Shoppers MUST be able to manage their notification preferences.

Platform and cross-cutting

- **FR-021**: Each bounded context (identity, catalogue, cart, order, payment, notification) MUST
  be an independently deployable service that owns its data; no two services MUST share a data
  store.
- **FR-022**: State changes that other services react to (order placed, payment approved or
  failed, order shipped, account registered) MUST be propagated as asynchronous events; consumers
  MUST tolerate duplicates.
- **FR-023**: All external traffic MUST enter through a single public entry point that routes to
  services; services MUST NOT be reachable directly from outside the platform network.
- **FR-024**: Service instances MUST be discovered automatically so that instances can be added or
  removed without configuration changes and traffic continues through remaining instances.
- **FR-025**: Every request MUST carry a correlation identifier across all participating services,
  and all services MUST emit structured logs collected into one central, searchable view.
- **FR-026**: Every service MUST expose readiness, liveness and request, error and latency
  metrics.
- **FR-027**: The entire platform MUST start locally with one documented command on a developer
  machine, including seeded demonstration data.
- **FR-028**: Every change to a service MUST run that service's quality gate and publish its
  container image independently of other services, with contract tests guarding each
  inter-service dependency.
- **FR-029**: The journeys above MUST be fully exercisable through the public API without a
  storefront; acceptance tests MUST be written in behaviour language per the constitution.

### Key Entities

- **Customer Account**: Identity with email, verification state, credentials, roles (shopper,
  operator), notification preferences and deletion state.
- **Address**: A delivery address belonging to an account.
- **Category**: A named grouping of products, possibly nested.
- **Product**: A sellable item with name, description, images, price, category and sale state.
- **Inventory Level**: Available and reserved quantities of a product, with an adjustment log.
- **Cart** and **Cart Line**: A shopper's intended purchase, anonymous or account-bound, with a
  revision identifier and lines holding product, quantity and price at time of adding.
- **Order** and **Order Line**: A confirmed purchase with frozen lines, totals, delivery address,
  order status, payment status, cancellation reason (when cancelled) and status history.
- **Payment Attempt**: A charge or refund against an order with provider reference, outcome and
  idempotency key.
- **Notification**: A message to a recipient on a channel, with delivery attempts and outcome.
- **Service Instance** and **Correlation Identifier**: Operational entities for discovery and
  tracing.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The full journey from browsing to a confirmed, paid order can be completed through
  the public API in under 5 minutes by a tester following the documentation.
- **SC-002**: 95% of catalogue browse and search requests complete in under 1 second with a
  catalogue of 10,000 products.
- **SC-003**: The platform sustains 1,000 concurrent shoppers browsing and 100 concurrent
  checkouts with no errors other than legitimate stock refusals.
- **SC-004**: Under a concurrent-checkout test on the last unit of a product, exactly one order
  succeeds in 100% of runs and stock is never negative.
- **SC-005**: 100% of order and account events produce a notification attempt within 30 seconds,
  and no event produces more than one message.
- **SC-006**: The platform starts locally with one command in under 5 minutes on a standard
  developer machine, with all health checks green.
- **SC-007**: For 100% of sampled requests, every participating service's log entries are
  retrievable from the central view by the request's correlation identifier.
- **SC-008**: Stopping one of two instances of any service during a load test causes zero
  client-visible errors after the in-flight requests of that instance.
- **SC-009**: A change to a single service triggers build, test and publication of only that
  service, completing within 15 minutes.
- **SC-010**: Zero unauthorised accesses succeed in a security test that attempts every operator
  capability as a shopper and every protected capability anonymously.

## Assumptions

- Scope is the MVP across all six bounded contexts as one umbrella feature; at planning time the
  work may be split into per-service plans and task lists, but this specification remains the
  single source of the journeys and contracts between them.
- The web storefront is out of scope and will be its own feature; everything here is exercised
  through the public API.
- Payments use a simulated provider only; integration with a real provider is a later feature.
  The simulator's rules (for example, amounts ending in specific digits are declined) are
  documented and deterministic.
- An account is required to place an order; browsing and the cart are anonymous-friendly.
- Single currency and single locale for the MVP; the currency is chosen at planning time.
- Delivery is a status in the order lifecycle only; carrier integration, shipping cost
  calculation and tax calculation are out of scope.
- Production orchestration, auto-scaling and multi-region deployment are out of scope; the MVP
  runs locally in containers and in the continuous-integration pipeline, with images published
  for a later deployment feature.
- The notification channels are email and SMS behind a provider abstraction, with a test sink in
  non-production environments; real provider credentials are a later concern.
- The constitution's rules apply throughout: hexagonal layers per service, value objects,
  deny-by-default security, the four test layers, mutation thresholds, structured logging and
  quiet builds.
- The build, harness and repository features (specs 001 to 003) are prerequisites and are not
  redefined here.
- "Payment pending" orders expire and release stock after 30 minutes unless the plan sets a
  different value.

# Feature Specification: Web Storefront and Local Development Bootstrap

**Feature Branch**: `005-storefront-dev-bootstrap` (spec directory; no branch was created by this command)

**Created**: 2026-10-04

**Status**: Implemented and validated live on 2026-10-07 (docs/validation/2026-10-storefront-run.md); SC-001 to SC-011 met on the development machine

**Input**: User description: "create frontend, and shell scripts to init development local enviroment"

This feature adds the two pieces that feature 004 (E-Commerce Platform MVP) left out on purpose:

1. **The web storefront**: the browser application through which shoppers (and, at lowest
   priority, operators) use the platform. Feature 004 delivered every journey through the public
   API only and recorded "the web storefront is out of scope and will be its own feature".
2. **Local development bootstrap**: scripts that take a fresh clone on a developer machine to a
   verified, running platform, and that keep that environment healthy afterwards. Today this is a
   page of manual steps spread across `docs/running-locally.md`, `docs/build.md`, `CONTRIBUTING.md`
   and the quickstart (install tools, create `.env`, generate a signing key, set engine-specific
   variables, activate the secret-scanning hook, start, wait, check).

Both pieces must keep every rule feature 004 established: a single public entry point, deny-by-
default authorisation enforced by the services, no personal data in logs, one-command local start,
and the repository-wide quality gate.

## Clarifications

### Session 2026-10-04

- Q: Should the operator console ship as part of this feature, or stay API-only for now? → A: A
  minimal console only: advancing and cancelling orders plus adjusting stock. Creating, editing,
  withdrawing and reinstating products and categories stay API-only and belong to a later feature.
- Q: When the bootstrap finds a missing tool, should it install it or only explain how? → A:
  Detect and print the remediation by default; install only with an explicit opt-in flag, through
  the system's package manager, announcing each step (FR-021 confirmed as written).
- Q: How long should a shopper stay signed in, and should there be a "keep me signed in" choice?
  → A: The session renews silently while the shopper is active, ends after 30 minutes of
  inactivity and when the browser is closed; there is no "keep me signed in" option.
- Q: Should the storefront report browser-side errors and timings into the platform's local
  observability stack? → A: Full browser telemetry: correlation identifier on every request,
  client-side errors, page-load and action timings, navigation paths and interaction events,
  all without personal data, visible in the local dashboards.
- Q: Should the bootstrap also prepare the self-hosted CI machine, or only developer laptops? → A:
  Developer machines, plus an explicit runner-host mode that also starts and checks the
  continuous-integration services (private image registry, contract broker) but does not register
  the runner; registration stays with the existing documented procedure.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse the store in a browser (Priority: P1)

A visitor opens the storefront, sees products with their picture, price and availability, browses by
category, pages through results, searches by name and opens a product's page, all without an
account.

**Why this priority**: Discovery is the entry point of every purchase. It is the first thing a
shopper, a stakeholder or a developer wants to see work, and it depends on nothing else in this
feature.

**Independent Test**: Start the local platform with seed data, open the storefront as an anonymous
visitor, list a category, search for a term and open a product page; the items, prices, images and
availability shown match what the public API returns for the same requests.

**Acceptance Scenarios**:

1. **Given** a seeded catalogue, **When** an anonymous visitor opens the storefront, **Then** the
   home page lists active products with name, price, primary image and availability and offers the
   categories to browse.
2. **Given** a category with more products than one page, **When** the visitor opens it, **Then**
   only that category's active products are shown, with controls to move between pages, and the
   current page and category remain visible in the browser address so the view can be shared.
3. **Given** a search term matching several products, **When** the visitor searches, **Then**
   matching products appear ranked as the platform ranks them, withdrawn products never appear, and
   an empty result shows a clear "nothing found" state instead of a blank page.
4. **Given** a product with zero stock, **When** its page is opened, **Then** it is marked out of
   stock and the "add to cart" action is unavailable.
5. **Given** a link to a product that does not exist or was withdrawn, **When** it is opened,
   **Then** the visitor sees a "product not found" page with a way back to browsing, not an error
   dump.
6. **Given** the platform is slow or unreachable, **When** a page loads, **Then** the visitor sees a
   loading state and then a readable error with a retry action; nothing freezes.

---

### User Story 2 - Build a cart and check out (Priority: P1)

A shopper adds products to a cart as an anonymous visitor, registers or signs in when ready to buy
(the anonymous cart is kept), chooses a delivery address and one of the available payment methods,
confirms the order and sees the confirmation with the order number and status.

**Why this priority**: This is the revenue journey and the reason the storefront exists. Together
with story 1 it makes the platform usable by a shopper without any API tooling.

**Independent Test**: As an anonymous visitor, add two products, change one quantity, remove one,
register a new account, verify the email through the local mail inbox, sign in, see the cart kept,
check out with the approving payment method and reach a confirmation page; then repeat with the
declining method and see the refusal explained.

**Acceptance Scenarios**:

1. **Given** an anonymous visitor, **When** they add a product, change its quantity and remove
   another, **Then** the cart reflects every change immediately, shows line and total prices, and
   survives a page reload and a browser restart on the same device.
2. **Given** an anonymous cart with lines, **When** the visitor registers (email and password),
   verifies the email through the link they receive and signs in, **Then** the anonymous lines are
   merged into the account cart (quantities summed up to the available stock) and the merge result
   is shown.
3. **Given** a signed-in shopper with a cart, **When** they open checkout, **Then** they can pick a
   saved address or enter a new one, pick one of the payment methods the platform offers for local
   use (no card number entry), and see the final amounts before confirming.
4. **Given** a product price changed after it was added to the cart, **When** the shopper confirms,
   **Then** checkout is refused with the changed lines and both prices listed, and the shopper
   must explicitly accept the new prices before resubmitting.
5. **Given** a line whose stock is no longer sufficient, **When** the shopper confirms, **Then** the
   refusal names the unavailable lines and the shopper can adjust the cart.
6. **Given** a successful confirmation, **When** the page is shown, **Then** it displays the order
   number, lines, amounts and both the order status and the payment status, and the cart is empty.
7. **Given** the declining payment method, **When** the shopper confirms, **Then** the result
   explains that payment was refused and that the order was cancelled, and the shopper can try again
   with another method from the still-intact cart.
8. **Given** the payment provider leaves a payment pending, **When** the shopper looks at the
   confirmation, **Then** the order shows "awaiting payment" and the time left before it expires,
   and the page updates when the status changes.
9. **Given** a confirmation was submitted and the response was lost (network error, double click,
   browser back and resubmit), **When** the shopper retries, **Then** exactly one order exists and
   the storefront shows that order.
10. **Given** the shopper's session expires during checkout, **When** they confirm, **Then** they
    are asked to sign in again and return to the same checkout with their entries kept.

---

### User Story 3 - Initialise a development machine with one command (Priority: P1)

A developer with a fresh clone runs one command. It tells them which prerequisites are missing or
misconfigured and exactly how to fix each one, prepares the local configuration and secrets, wires
the clone's safety hooks, optionally starts the whole platform including the storefront, waits for
it to be healthy, checks it and prints where everything is.

**Why this priority**: Every contributor, reviewer and the CI runner host go through this. The
steps exist today but are manual, spread over four documents, engine-specific and easy to get
subtly wrong (a missing image-format variable makes the stack never become healthy; a missing
signing key makes identity never start).

**Independent Test**: On a machine that has the prerequisites, clone the repository, run the
command, and reach a healthy platform with the storefront opened in a browser, without reading any
document. On a machine missing a prerequisite, run it and receive a precise list of what to install,
with the command exiting without changing anything.

**Acceptance Scenarios**:

1. **Given** a fresh clone and all prerequisites present, **When** the developer runs the bootstrap
   command, **Then** every check is reported as passed, the local configuration file is created
   from the example with a freshly generated signing key, the clone's safety hooks are active, and
   the command ends by stating the next step (start) or, with the start option, with a running
   healthy platform and the storefront, gateway, mail inbox and dashboards addresses printed.
2. **Given** a missing or wrong-version prerequisite (runtime, container engine, compose provider,
   package manager for the storefront, secret scanner, command-line tools), **When** the bootstrap
   runs, **Then** each failing item is named with the found and expected values and the exact
   remediation for the developer's operating system, nothing is installed or modified, and the
   exit status distinguishes "prerequisites missing" from other failures.
3. **Given** the developer opts in to automatic installation, **When** the bootstrap runs, **Then**
   it installs only the listed missing tools through the platform's standard package manager,
   states what it is about to do before each installation, never acquires elevated privileges
   silently, and re-checks afterwards.
4. **Given** a container engine that is installed but not running, has too little memory or disk
   for the stack, or has a known engine-specific quirk (image format, test-container reaper, port
   forwarding limits), **When** the bootstrap runs, **Then** it reports the condition with the
   recommended setting and, where the fix is a local configuration value, applies it with the
   developer's consent.
5. **Given** the bootstrap already ran, **When** it runs again, **Then** it changes nothing that is
   already correct (existing configuration values and secrets are kept), reports "nothing to do"
   for those items and finishes quickly.
6. **Given** the port the public entry point uses is taken, **When** the bootstrap starts the
   platform, **Then** it detects the conflict before starting, proposes a free port, records it in
   the local configuration and uses it everywhere it prints addresses.
7. **Given** a dry-run option, **When** the bootstrap runs, **Then** every change it would make is
   printed and none is made; checks still run.
8. **Given** a non-interactive run (no terminal, or an explicit flag), **When** a decision would
   require consent, **Then** the safe default is taken (do not install, do not reset) and the choice
   is reported.
9. **Given** the self-hosted CI machine, **When** the bootstrap runs in its explicit runner-host
   mode, **Then** the same prerequisite checks run, the continuous-integration services (private
   image registry, contract broker) are started and checked in addition to the platform, their
   addresses are printed, and the output ends by pointing at the documented runner-registration
   procedure without asking for or handling a registration token.

---

### User Story 4 - Manage the account and follow orders (Priority: P2)

A signed-in shopper reviews past orders and their current status, cancels an order while that is
still allowed, manages addresses and notification preferences, resets a forgotten password and
deletes the account.

**Why this priority**: Completes the shopper's self-service loop after a purchase. It depends on
story 2's sign-in and is valuable on its own once orders exist.

**Independent Test**: With an account that has placed orders, open the order list, open one order,
cancel a `placed` order, change an address and a notification preference, sign out, use "forgot
password" through the local mail inbox and sign back in with the new password.

**Acceptance Scenarios**:

1. **Given** a shopper with several orders, **When** they open their orders, **Then** only their own
   orders are listed, newest first, each with order status and payment status, and opening one shows
   its lines, amounts, address and status history.
2. **Given** an order still `placed`, **When** the shopper cancels it, **Then** they confirm the
   action first, the order becomes `cancelled`, and the cancel action disappears once the order
   is `preparing` or later (operators may still cancel while `preparing`, as the platform
   defines).
3. **Given** the shopper is signed in, **When** they change addresses or notification preferences,
   **Then** the change is confirmed on screen and is what the next checkout and the next
   notification use.
4. **Given** a shopper who forgot the password, **When** they request a reset, **Then** the
   storefront answers the same way whether or not the email exists, the link received works once,
   and the shopper can sign in with the new password.
5. **Given** a shopper requests account deletion, **When** they confirm with their password,
   **Then** they are signed out, cannot sign in again, and their orders remain for the operator
   without personal details.

---

### User Story 5 - Keep the local environment healthy (Priority: P2)

A developer checks the state of their local platform, resets it to a clean seeded state, updates it
after pulling changes, and tears it down, each with one command and a clear summary.

**Why this priority**: The daily loop after the first bootstrap. Without it developers rediscover
`down -v`, image rebuilds and quirk fixes from documentation every time.

**Independent Test**: With a running platform, run the status command and see every component
healthy with its address; stop one service and see it reported; run the reset command, confirm, and
reach a freshly seeded platform; run the teardown and see nothing left running.

**Acceptance Scenarios**:

1. **Given** a running or partly running platform, **When** the developer asks for status, **Then**
   each component is listed as healthy, starting, unhealthy or stopped, with the public addresses,
   the free resources of the container engine, and the same prerequisite checks as the bootstrap.
2. **Given** a running platform with data, **When** the developer resets it, **Then** they are asked
   to confirm the loss of local data (unless the non-interactive flag with explicit consent is
   given), the platform is stopped, data removed, rebuilt where sources changed, restarted and
   reseeded, and the final state is checked as in the bootstrap.
3. **Given** the developer pulled changes that touch services or the storefront, **When** they ask
   for an update, **Then** only the changed components are rebuilt and restarted, data is kept, and
   the result is checked.
4. **Given** a developer is done, **When** they tear down, **Then** nothing of the platform keeps
   running, and they are told whether data was kept or removed.

---

### User Story 6 - Fulfil orders and adjust stock from a minimal console (Priority: P3)

An operator signs in to a restricted console, lists all orders, advances an order's status,
cancels orders and adjusts the stock of existing products. Creating, editing, withdrawing and
reinstating products and categories are not part of this console: operators keep doing that
through the API until a later feature adds catalogue editing.

**Why this priority**: Operators can already perform every operation through the API, so the
console is convenience rather than capability. It is last so that the shopper journeys and the
developer bootstrap ship first; it can be dropped or deferred without affecting them. It is kept
minimal (fulfilment and stock) because those are the daily actions; catalogue editing is rarer and
has far more screens.

**Independent Test**: Sign in as the seeded operator, open an order placed by a shopper, advance it
to `shipped` and see the shopper's order page reflect it; then raise the stock of a sold-out product
and see it become addable to carts again.

**Acceptance Scenarios**:

1. **Given** an account without the operator role, **When** it opens the console address, **Then**
   it is refused and nothing of the console's data is shown; the refusal is enforced by the
   platform, not only hidden by the storefront.
2. **Given** an operator, **When** they list orders, **Then** every shopper's orders are shown with
   both statuses, can be filtered by status, and opening one shows lines, amounts, address and
   status history without exposing more personal data than the order itself carries.
3. **Given** an order, **When** the operator advances its status, **Then** only the transitions the
   platform allows are offered, the operator confirms the action, and a refused transition is
   explained.
4. **Given** an order in `placed` or `preparing`, **When** the operator cancels it, **Then** they
   confirm first and the shopper's order page shows `cancelled` afterwards.
5. **Given** an existing product, **When** the operator adjusts its stock (with a reason), **Then**
   validation errors are shown next to the field concerned and the new availability is visible to
   shoppers immediately.
6. **Given** an operator, **When** they look for a way to create, edit or withdraw a product or
   category, **Then** the console offers none and states that catalogue editing is done through
   the API for now.

---

### Edge Cases

- **Session expiry or sign-out in another tab**: any action on a protected page leads to a sign-in
  prompt that returns to the same place; no half-applied actions.
- **Throttling**: when the platform throttles sign-in, registration or browsing, the storefront
  shows how long to wait and does not hammer the platform with automatic retries.
- **Stale cart**: a cart line whose product was withdrawn or ran out of stock is flagged and
  excluded from checkout until removed; the shopper is told why.
- **Deep links**: product, category, order and verification links work when opened directly or
  after a reload, including with a non-default public port.
- **Email links from the local mail inbox**: verification and reset links open the storefront on
  the address the developer configured, including a changed port.
- **Browser without stored state** (private window, cleared storage): the storefront works as a new
  anonymous visitor; nothing breaks.
- **Content with markup or scripts in product names, descriptions or search terms**: shown as
  text, never interpreted.
- **Narrow screens**: every journey remains usable on a 360-pixel-wide viewport.
- **Bootstrap interrupted midway**: rerunning completes the remaining steps; partially written
  configuration is never left in an unreadable state.
- **Bootstrap on an unsupported operating system**: a clear statement of the supported systems and
  what to do (for example, use a Linux environment on Windows).
- **No network while bootstrapping**: checks that need downloads report "could not verify, no
  network" rather than failing as "missing".
- **Container engine reachable only through a compatibility layer** (for example a Podman socket
  presented as a Docker socket): detected and handled as that engine, with its quirks.
- **Reset requested while tests or another build are using the engine**: the developer is warned
  about the running containers that are not part of the platform; those are never touched.

## Requirements *(mandatory)*

### Functional Requirements

**Storefront: browsing (US1)**

- **FR-001**: The storefront MUST let an anonymous visitor list active products with name, price,
  primary image and availability, browse by category, page through results and search by name,
  showing exactly what the platform returns (ranking, paging, withdrawn products excluded).
- **FR-002**: Product pages MUST show description, images, price and availability; out-of-stock
  products MUST NOT be addable to the cart; missing or withdrawn products MUST show a not-found
  page.
- **FR-003**: Category, page and search state MUST be reflected in the browser address so views can
  be shared, reloaded and navigated back and forth.

**Storefront: cart, identity and checkout (US2, US4)**

- **FR-004**: Anonymous and signed-in shoppers MUST be able to add, change the quantity of and
  remove cart lines, see current prices and totals, and have the cart survive reloads and browser
  restarts on the same device; on sign-in the anonymous cart MUST be merged into the account cart
  as the platform defines and the result shown.
- **FR-005**: Shoppers MUST be able to register, verify their email via the link they receive, sign
  in, sign out, reset a forgotten password and delete their account from the storefront; the
  storefront MUST NOT reveal whether an email is registered through any of these flows.
- **FR-006**: Checkout MUST let a signed-in shopper choose or enter a delivery address, choose a
  payment method among those the platform offers (locally, the simulated methods; no card number is
  ever typed or stored), review amounts and confirm.
- **FR-007**: A confirmation that the platform refuses because prices changed MUST show old and new
  prices per line and require explicit acceptance before resubmission; a refusal for insufficient
  stock MUST name the lines.
- **FR-008**: Repeating a confirmation after a lost response, a double submission or browser
  navigation MUST never create a second order; the storefront MUST reuse the same idempotency
  identity for retries of an unchanged request and MUST show the single resulting order.
- **FR-009**: The confirmation and order pages MUST show both order status and payment status,
  including "awaiting payment" with the remaining time, and MUST refresh when the status changes
  without requiring a manual reload.
- **FR-010**: Shoppers MUST see only their own orders, MUST be able to cancel while the platform
  allows it (after confirming the action), and MUST be able to manage addresses and notification
  preferences.

**Storefront: operator console (US6)**

- **FR-011**: A console area MUST let operators list and filter all orders, advance order status,
  cancel orders and adjust the stock of existing products (with a reason), offering only the
  transitions the platform allows, asking for confirmation before each status change, and showing
  validation errors next to the field concerned. The console MUST NOT offer creating, editing,
  withdrawing or reinstating products or categories; those remain API-only in this feature.
- **FR-012**: The console MUST be unavailable to anyone without the operator role; the refusal MUST
  come from the platform, and the storefront MUST additionally hide console entry points from
  non-operators.

**Storefront: cross-cutting**

- **FR-013**: All storefront traffic MUST enter the platform through the single public entry point;
  the storefront MUST be served as part of the locally started platform at that same entry point,
  so the existing one-command start also serves the storefront, and no second origin or port is
  needed for shoppers.
- **FR-014**: Session credentials MUST NOT be readable by page scripts, MUST end on sign-out and
  MUST be bound so that a request from another site cannot act as the shopper. A session MUST
  renew silently while the shopper is active, MUST end after 30 minutes of inactivity and when
  the browser is closed, and the storefront MUST NOT offer a "keep me signed in" option.
- **FR-015**: Everything displayed from platform data or user input MUST be rendered as text, never
  interpreted as markup or script.
- **FR-016**: Every list and action MUST have loading, empty, error and "throttled, retry in N
  seconds" states; errors MUST be readable by a shopper and MUST NOT expose internal details.
- **FR-017**: The core shopper journeys (US1, US2, US4) MUST be operable by keyboard alone, with
  labelled controls, visible focus and sufficient contrast, and MUST be usable from 360 to 1440
  pixels of viewport width.
- **FR-018**: The storefront MUST be covered by the repository's single quality-gate command, which
  MUST stay silent on success; the storefront's acceptance scenarios MUST be written as user
  behaviour, and every edge between the storefront and a platform capability MUST have a
  consumer-driven contract verified by that capability's provider.
- **FR-019**: The storefront MUST be built and started as part of the local platform images, so the
  cold start of the whole platform stays within the limit feature 004 set (SC-006 there).
- **FR-031**: The storefront MUST send the platform's correlation identifier with every request and
  MUST report browser telemetry to the platform's observability stack: client-side errors (failed
  actions, script errors), page-load and action timings, navigation paths and interaction events,
  each carrying the correlation identifier of the request it relates to, so that one shopper
  action can be followed from the browser through every service in the existing dashboards.
- **FR-032**: Browser telemetry MUST NOT contain personal data, credentials, form contents, search
  terms or free text typed by the shopper; it MUST identify a session only by a random identifier
  that is not the session credential, MUST be accepted by the platform only through the public
  entry point with the same throttling as other anonymous traffic, and MUST be dropped silently
  by the storefront (never retried in a loop, never blocking a page) when it cannot be delivered.

**Local development bootstrap (US3, US5)**

- **FR-020**: One command MUST check every prerequisite of a developer machine, each with found
  value, expected value and operating-system-specific remediation: the pinned runtime version, the
  container engine and its compose provider (running, memory, CPU, free disk), the storefront's
  package manager and runtime version, the secret scanner, and the command-line tools the
  repository scripts use.
- **FR-021**: The bootstrap MUST only detect and report by default; it MUST install missing tools
  only when the developer explicitly opts in, MUST announce each installation before performing it,
  MUST use the platform's standard package manager, and MUST never acquire elevated privileges
  without an explicit prompt.
- **FR-022**: The bootstrap MUST create the local configuration from the committed example when
  missing, generate every required secret (at minimum the token-signing key) when empty, never
  print or commit a secret, and never overwrite a value that is already set.
- **FR-023**: The bootstrap MUST activate the clone's committed safety hooks and apply the
  engine-specific local settings the platform needs (image format on engines that drop health
  checks, test-container reaper behaviour, host-alias and port-forward notes), with the developer's
  consent for anything outside the repository.
- **FR-024**: With a start option, the bootstrap MUST start the platform including the storefront,
  wait until every component is healthy, run the smoke checks (public entry point answers, no
  service port exposed, storefront loads) and print the public addresses; a free public port MUST
  be proposed and recorded when the default is taken.
- **FR-025**: Every bootstrap and maintenance command MUST be idempotent (rerunning changes nothing
  that is already correct), MUST offer a dry run that prints changes without making them, and MUST
  behave safely without a terminal (no install, no data loss, choices reported).
- **FR-026**: Exit statuses MUST distinguish success, prerequisites missing, platform failed to
  start or to pass checks, and usage error, so the commands can be used by other scripts and by CI.
- **FR-027**: Maintenance commands MUST provide status (component health, addresses, engine
  resources, prerequisite checks), reset (stop, remove data after confirmation, rebuild what
  changed, reseed, check), update (rebuild and restart only changed components, keep data) and
  teardown (stop everything, say whether data was kept).
- **FR-028**: The commands MUST support macOS and Linux shells used by the project; containers
  that are not part of the platform MUST never be stopped or removed.
- **FR-029**: The scripts MUST have automated tests that run without network access and without a
  container engine (checks, decisions, dry runs and idempotency with stubbed tools), and MUST pass
  the repository's script linting as part of the quality gate.
- **FR-030**: The contributor and running-locally documentation MUST present the bootstrap command
  as the first step and keep the manual steps only as the explanation of what the command does.
- **FR-033**: The bootstrap MUST offer an explicit runner-host mode, never selected by default,
  that runs the same checks and preparation as for a developer machine and additionally starts
  and health-checks the continuous-integration services (private image registry, contract
  broker); it MUST NOT register the runner or accept a registration token, and MUST end by
  referring to the documented registration procedure.

### Key Entities

- **Storefront session**: the signed-in state of a shopper or operator in one browser; carries the
  role, the idle expiry (30 minutes, renewed by activity, never beyond the browser session) and
  the anonymous cart it was merged from. Never contains the password.
- **Cart view**: the lines, quantities, current prices, flags (price changed, unavailable) and the
  revision the shopper last saw, as the platform reports them.
- **Checkout draft**: the shopper's choices before confirmation: delivery address, payment method,
  acknowledged cart revision, and one idempotency identity per distinct confirmation request,
  reused for every retry of that same request and replaced when the request changes (for example
  after accepting new prices).
- **Order view**: an order as shown to a shopper or operator: number, lines, amounts, address,
  order status, payment status, payment deadline, status history and allowed actions.
- **Environment check**: one prerequisite or setting: name, found value, expected value, result
  (pass, fail, cannot verify), remediation text per operating system, whether it can be fixed
  automatically.
- **Local environment configuration**: the developer's local values (public port, seed on or off,
  engine-specific settings) and generated secrets; never committed, never printed.
- **Platform component status**: a component of the locally running platform with its state
  (healthy, starting, unhealthy, stopped) and public address if any.

## Threat Model *(constitution Principle III)*

**Assets**: shopper credentials and sessions; personal data shown in the storefront (email,
addresses, order history); operator privileges and the catalogue and orders they control; locally
generated secrets (token-signing key, internal token, dashboard password); the developer machine
and its container engine.

**Actors**: anonymous visitor; shopper; operator; an attacker who can serve content to the shopper's
browser or sit on the same network; a compromised third-party package pulled into the storefront or
the scripts; a developer running the bootstrap on their own machine.

**Abuse cases and required defences**

| Abuse case | Defence required by this spec |
|------------|-------------------------------|
| Steal a session through script injected via product content or search terms | FR-014 (credentials unreadable by scripts), FR-015 (all content rendered as text) |
| Act as the shopper from another site (cross-site request) | FR-014 (session bound against cross-site requests) |
| Learn which emails are registered | FR-005 (uniform responses) |
| Reach the operator console as a shopper | FR-012 (platform enforces; storefront hides) |
| Create duplicate orders by replaying a confirmation | FR-008 (single idempotency identity) |
| Flood sign-in or browsing from the storefront | FR-016 (throttling honoured, no automatic hammering) |
| Leak personal data or credentials through browser telemetry, or flood the telemetry intake | FR-032 (no personal data or typed text, random session identifier, throttled intake, silent drop) |
| Secrets leak through bootstrap output, logs or a commit | FR-022 (never printed or committed), FR-023 (secret-scanning hook activated) |
| Bootstrap silently installs or runs software with elevated rights | FR-021 (opt-in, announced, standard package manager, explicit prompt) |
| Bootstrap destroys the developer's unrelated containers or data | FR-027 (confirmation before data loss), FR-028 (only platform containers) |
| Malicious dependency in the storefront or script toolchain | Dependencies scanned in CI (constitution III); lock files committed; the quality gate fails on a critical finding |

**Trust boundaries**: browser ↔ public entry point (the only network boundary the storefront
crosses); storefront ↔ platform capabilities (authorisation decided by the platform, never by the
storefront); developer shell ↔ bootstrap scripts ↔ container engine and package manager (every
mutation announced, consented or dry-run); repository ↔ third-party package registries.

**Personal data classification**: email (identifier, shown only to its owner and operators),
password (entered, never stored or logged by the storefront), delivery addresses and order history
(personal, shown only to the owner and operators). Payment data is limited to the platform's
simulated method identifiers; no card data exists anywhere in this feature. Browser telemetry
(FR-031) carries page names, timings, interaction kinds and a random session identifier only:
it is not personal data by construction (FR-032).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with the prerequisites installed goes from a fresh clone to a running,
  healthy platform with the storefront open in a browser in under 30 minutes, including the image
  build, using one command and without reading any document.
- **SC-002**: On a machine missing a prerequisite, the bootstrap reports every missing item with
  its remediation in under 30 seconds and changes nothing; a rerun on a prepared machine finishes
  in under 10 seconds and reports nothing to do.
- **SC-003**: A first-time shopper completes browse → cart → register → verify → sign in → checkout
  → confirmation → order history entirely in the storefront in under 5 minutes.
- **SC-004**: 95 % of storefront page loads on the local platform show their content within
  2 seconds, and 95 % of actions (add to cart, search, sign in) reflect their result within
  1 second, measured from the browser telemetry of real sessions (FR-031) in the local
  dashboards.
- **SC-011**: 100 % of storefront requests and reported browser events carry a correlation
  identifier that joins them to the services' log lines and traces in the existing dashboards,
  and a scan of the telemetry collected during a full shopper journey finds zero personal data.
- **SC-005**: 100 % of feature 004's shopper acceptance scenarios (its user stories 1 to 6) can be
  reproduced through the storefront, and 100 % of its operator order-fulfilment and
  stock-adjustment scenarios (the relevant parts of story 7) through the console; catalogue
  editing scenarios stay API-only.
- **SC-006**: The core shopper journeys show zero critical or serious accessibility violations in an
  automated audit and can be completed with the keyboard alone.
- **SC-007**: 100 % of storefront-to-platform edges have a consumer-driven contract that the
  provider verifies in the quality gate; a breaking change fails the gate.
- **SC-008**: 100 % of generated secrets are absent from bootstrap output, logs and tracked files,
  verified by the existing secret scanner over a full bootstrap run.
- **SC-009**: Adding the storefront keeps the one-command cold start of the whole platform under
  the 5-minute limit of feature 004 (SC-006 there).
- **SC-010**: Every bootstrap and maintenance command is idempotent and dry-runnable: a second run
  and a dry run produce zero configuration changes, verified by automated tests that need neither
  network nor container engine.

## Assumptions

- **Audience of the storefront**: shoppers first. The operator console (US6) is included at the
  lowest priority and limited to order fulfilment and stock adjustment (clarified 2026-10-04);
  catalogue editing stays API-only until a later feature. The console can be deferred without
  affecting the shopper stories.
- **Install policy of the bootstrap**: detect-and-guide by default, install only on explicit
  opt-in (FR-021). This keeps the command safe to run on any machine, including the self-hosted
  runner host.
- **Runner host**: the bootstrap prepares the self-hosted CI machine only up to running the
  continuous-integration services; registering the runner stays a documented manual step so the
  script never handles a registration token (clarified 2026-10-04).
- **Supported developer systems**: macOS and Linux. Windows is supported only through a Linux
  environment (WSL); native Windows shells are out of scope.
- **Platform capabilities are reused unchanged**: the storefront consumes the public API and the
  seed data of feature 004 (categories, products, operator account, simulated payment methods).
  Where a storefront need is not met by an existing capability, the gap is recorded for the
  platform rather than worked around in the storefront.
- **Payment entry**: locally the shopper selects one of the simulated payment methods by name
  (approving, declining, pending); the storefront never collects card numbers.
- **Language and currency**: English only, one currency, as the platform's seed data defines.
- **Email delivery**: locally, verification and reset emails arrive in the platform's local mail
  inbox; the storefront's links point at the configured public address and port.
- **Account deletion** follows feature 004 FR-007 (personal data anonymised, orders kept).
- **Out of scope**: production hosting and content delivery, search-engine optimisation, marketing
  content management, analytics, native mobile applications, real payment providers, multiple
  languages or currencies, and Windows-native tooling.
- **Dependencies**: feature 004's platform, contracts and seed data; the repository's existing
  quality gate, which already expects a storefront with `lint` and `test` entry points
  (`frontend/README.md`); the existing local-start scripts and documentation, which the bootstrap
  wraps rather than replaces; constitution Principle VII for the storefront's quality bar.

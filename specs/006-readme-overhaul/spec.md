# Feature Specification: README Overhaul

**Feature Branch**: `006-readme-overhaul` (spec directory; no branch was created by this command)

**Created**: 2026-10-08

**Status**: Implemented on 2026-10-08 (T001–T024); T025 rendering review pending a push

**Input**: User description: "better README.MD, make it easy for people undestand how to run, better organize content, use bagdes, use graphics or github flavored md."

## Clarifications

### Session 2026-10-08

- Q: Should the README rules be enforced by automated checks that run with the existing repository script tests? → A: Yes — extend the existing README test: relative links resolve, section order, badge versions match the version sources (offline; no external-URL checking).
- Q: How many build-status badges should the README show at the top? → A: One main build badge in the header; one status badge per service in the services table.
- Q: Besides the architecture overview, should the README include a second diagram showing how a purchase flows through the services? → A: Yes — one purchase-flow sequence diagram, in a collapsed section.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - First-time visitor runs the platform (Priority: P1)

A developer who has never seen the repository opens its front page and, using only that page, learns what they must
have installed, runs the platform with a single start command, opens the storefront in a browser, and knows how to
check its state and stop it.

**Why this priority**: Running the platform is the first thing every visitor tries. Today the quick start sits below
the architecture prose and part of the page is out of date, so newcomers stumble before seeing anything work.

**Independent Test**: Give the page to someone unfamiliar with the project on a machine that meets the prerequisites;
they reach the running storefront without opening any other document.

**Acceptance Scenarios**:

1. **Given** a visitor lands on the repository page, **When** they read past the title and short introduction,
   **Then** the quick start is the next substantive section and is visible without scrolling past architecture details.
2. **Given** a visitor checks the prerequisites, **When** they read the prerequisites list, **Then** every required
   tool appears with its required version and why it is needed, and the page says the start command checks them.
3. **Given** a visitor has started the platform, **When** they look for where to go next, **Then** a table lists each
   local address (storefront and API, dashboards, test mailbox) with its purpose.
4. **Given** a visitor wants to stop or inspect the platform, **When** they read the quick start, **Then** the status
   and stop commands are present with a one-line description of their effect.

---

### User Story 2 - Evaluator grasps the system at a glance (Priority: P2)

A reviewer (recruiter, peer engineer, potential contributor) wants to understand within a couple of minutes what the
project is, how healthy it is, what it is built with, and how its parts fit together.

**Why this priority**: The page is the project's shop window; status indicators and a picture of the architecture
convey quality and scope far faster than paragraphs.

**Independent Test**: Show the page to a reviewer for two minutes, then ask them to name the services and how they
communicate; they answer correctly.

**Acceptance Scenarios**:

1. **Given** a reviewer opens the page, **When** they look under the title, **Then** they see badges for licence, main
   build status, and the main language and runtime versions.
2. **Given** a reviewer reads the architecture section, **When** the page renders on the hosting site, **Then** a
   diagram shows the storefront, the gateway, each service, the event bus and the per-service data stores.
4. **Given** a reviewer wants to see how a purchase works, **When** they expand the purchase-flow section, **Then** a
   sequence diagram shows the order of calls and events from cart to notification.
3. **Given** a reviewer wants to know each service's job, **When** they read the services table, **Then** each
   bounded context has a one-line responsibility and its own build-status badge.

---

### User Story 3 - Contributor finds the right deeper document (Priority: P3)

A contributor who already ran the platform wants to build and test a single module, understand CI, or read the
contribution rules, and needs the page to route them to the right detailed document.

**Why this priority**: Detailed documentation already exists; the front page only needs to be an accurate index, so
this is valuable but lower impact than running and understanding the platform.

**Independent Test**: Ask a contributor to find the instructions for running one test layer of one service and the
CI documentation, starting from the page; each is reached in one click.

**Acceptance Scenarios**:

1. **Given** a contributor opens the page, **When** they look at the top, **Then** a table of contents links to every
   top-level section.
2. **Given** a contributor wants build and test commands, **When** they read the build section, **Then** it shows the
   current whole-repository gate command and how to run one layer of one module, matching the build documentation.
3. **Given** a contributor wants more depth, **When** they read the documentation map, **Then** a table links each
   detailed document (build, development environment, running locally, architecture, CI/CD, storefront, gateway,
   contributing, security) with a one-line description.

---

### Edge Cases

- A prerequisite is missing or the container engine has too little memory: the page warns about the memory need up
  front and links to the troubleshooting and manual-start material instead of reproducing it.
- The visitor uses an alternative container engine that needs an extra setting: a highlighted note names the need and
  links to the detail.
- The main build is failing or a workflow is renamed: the status badge must point at a workflow that exists, so it
  shows a real state rather than a broken image.
- The hosting site renders the page in dark mode or on a phone: diagrams are text-based and themed by the site, no
  images with hard-coded light backgrounds, and tables stay narrow enough to read.
- Long optional material (manual start, purchase flow, how the repository was published) is collapsed so it does not push the
  primary path down the page.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The page MUST present sections in this order: title with one-line pitch and badges; table of contents;
  overview; quick start (prerequisites, start, addresses, status/stop); architecture with diagram and services table;
  repository layout; build and test; documentation map; contributing; licence.
- **FR-002**: The page MUST include a table of contents whose entries link to every top-level section.
- **FR-003**: The page MUST show header badges for the licence (keeping the existing MIT licence badge), exactly one
  main build status (the whole-repository gate), and the main language, runtime and frontend versions; every version shown MUST equal the version declared
  in the repository's single version sources at the time of writing.
- **FR-004**: The page MUST list prerequisites in a table with tool, required version and purpose, and state that the
  start command verifies them and explains any fix.
- **FR-005**: The quick start MUST get a prepared machine to a running storefront in at most three commands, each in
  its own copyable code block, each followed by its expected outcome.
- **FR-006**: The page MUST list the local addresses (storefront and API, dashboards, test mailbox) in a table with
  their purpose.
- **FR-007**: The page MUST include exactly two diagrams that the hosting site renders natively from text kept in the
  repository (no externally hosted images): an always-visible architecture overview, and a purchase-flow sequence
  (browse, cart, order, payment, notification, with the events exchanged) inside a collapsed section.
- **FR-008**: The page MUST include a services table with each bounded context, a one-line responsibility and that
  service's own build-status badge.
- **FR-009**: The build and test section MUST show the current whole-repository gate command and the per-layer
  commands, consistent with the build documentation.
- **FR-010**: The page MUST use highlighted callouts (note, tip, warning) for hardware/memory and container-engine
  caveats, and collapsible sections for long optional material (manual start, purchase flow, repository publishing).
- **FR-011**: The page MUST keep every element the repository's existing documentation checks require: a top-level
  title, the words "architecture", "build" and "verify", a link to the constitution, a link to the specifications
  directory, the repository publishing script name, and the MIT licence badge.
- **FR-012**: The page MUST correct facts that are currently stale (framework major version, the gate command, and
  the statement that the build is not yet present).
- **FR-013**: The page MUST link to detailed documents rather than duplicate them, and stay at or under about 250
  lines of source.
- **FR-014**: Every relative link on the page MUST resolve to a file or directory that exists in the repository.
- **FR-015**: The repository's existing offline README test MUST be extended to fail when a relative link does not
  resolve, when the top-level sections are missing or out of the FR-001 order, or when a version badge differs from
  the repository's version sources. External URLs are not checked.

### Key Entities

- **Section**: a top-level part of the page, with a heading, a table-of-contents entry and a position in the order.
- **Badge**: a small status or version indicator with a label, a value and a link target.
- **Diagram**: a text-defined picture rendered by the hosting site; two kinds: architecture overview and purchase-flow
  sequence.
- **Documentation map entry**: a link to a detailed document with a one-line description of when to read it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A newcomer on a machine meeting the prerequisites reaches the running storefront following only the
  page, using at most three commands.
- **SC-002**: The quick start begins within the first 40 lines of the rendered page.
- **SC-003**: 100% of relative links resolve, and zero version or command statements contradict the repository's
  build sources.
- **SC-004**: Every assertion the repository's documentation test already makes is kept and passes, and the
  extended checks (FR-015) pass against the new page and fail against a deliberately broken copy.
- **SC-005**: A reviewer can name the services and how they communicate within two minutes of opening the page.
- **SC-006**: A contributor reaches the build, CI and contributing documents in one click each from the page.

## Assumptions

- The audience is developers and evaluators viewing the repository on its hosting site; the page is written in
  English only.
- Scope is the root README. Other documents are linked, not rewritten; only links that would break are adjusted.
- "Graphics" means text-based diagrams and native rich formatting (tables, callouts, collapsible sections, badges);
  no binary images, screenshots or external image hosts other than the badge service.
- The existing start command remains the primary way to run the platform; the manual procedure stays in its
  existing document and is only summarised in a collapsed section.
- Version badges are written as static values sourced from the version catalog and the frontend package manifest;
  drift is caught by the extended README test (FR-015), not by generating the badges.

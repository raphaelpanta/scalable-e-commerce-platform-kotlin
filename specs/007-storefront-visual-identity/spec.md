# Feature Specification: Storefront Visual Identity

**Feature Branch**: `007-storefront-visual-identity`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "better frontend design. The actual design is functional but also generic. Plan for a better design."

## Clarifications

### Session 2026-10-08

- Q: Which visual direction should the redesign aim for? → A: Warm editorial — serif display headings,
  warm off-white "paper" background, deep ink text, a single accent colour, generous whitespace,
  imagery-forward product presentation.
- Q: Which surfaces are in scope? → A: Full redesign of every shopper-facing page; the operator console only
  inherits the new design tokens and typography (no layout redesign).
- Q: Should the store get a real brand identity instead of "Storefront"? → A: Yes — a fictional brand with a
  name, wordmark and tagline.
- Q: Should "Vibestore" replace only the name shoppers and operators see, or also the internal "storefront"
  name used in code, contracts, CI and docs? → A: User-visible brand only; the store is named "Vibestore",
  and internal technical names (web app package, contract participant, CI workflow, docs, test steps) stay
  "storefront".
- Q: What tagline should appear under the Vibestore wordmark on the home page? → A: "Good things, good vibes".

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A recognisable, editorial storefront while browsing (Priority: P1)

A shopper lands on the home page and immediately sees a store with its own personality: the Vibestore
wordmark and its tagline "Good things, good vibes", a warm paper-toned background, elegant serif headings
paired with a calm, readable body face, and a single confident accent colour. Below an editorial
introduction that highlights a few featured categories, products appear as image-led cards — photograph
first, then name, price and availability — with generous spacing. Category and search pages share the same
look and rhythm.

**Why this priority**: Browsing is the first and most frequent experience; it is where "generic" is felt
most and where a distinctive identity pays off immediately. On its own it already transforms the
impression of the whole store.

**Independent Test**: Open the home, a category and a search results page at phone and desktop widths and
confirm the brand header, editorial introduction, type pairing, palette and image-led cards are present and
consistent, while every existing browse scenario still passes.

**Acceptance Scenarios**:

1. **Given** a shopper opens the home page, **When** it finishes loading, **Then** the header shows the
   Vibestore wordmark (linking home) and the page opens with an editorial introduction containing the
   tagline and links to featured categories taken from the existing catalogue.
2. **Given** the catalogue has active products, **When** the shopper views any product listing, **Then** each
   card shows the product image at a consistent aspect ratio, followed by the name, price and availability,
   and the whole card area is a single, clearly focusable link target to the product.
3. **Given** a product has no image or its image fails to load, **When** its card renders, **Then** a branded
   placeholder of the same size appears with an accessible description, and the grid does not shift.
4. **Given** the shopper moves between home, category and search pages, **When** comparing them, **Then**
   headings, spacing, grid and card styling are identical in treatment.
5. **Given** the browser title bar or tab, **When** any page is open, **Then** it shows the Vibestore name and
   brand icon instead of "Storefront".

---

### User Story 2 - Product, cart and checkout feel polished and trustworthy (Priority: P2)

On the product page the shopper sees a large image, a clear typographic hierarchy (name, price, then the
primary "add to cart" action), and supporting details set in the editorial style. In the cart and checkout,
every button, field, badge, dialog, pager and summary belongs to one visual family. Waiting moments show
calm skeleton placeholders shaped like the content to come; empty and error states speak in the brand's
voice with a clear next step.

**Why this priority**: These pages carry money and personal data; visual coherence and clarity here
directly support trust and conversion. They build on the identity established in Story 1.

**Independent Test**: Walk one product from product page to order confirmation (including a declined
payment and an empty cart) and confirm every control and state uses the shared component family, with no
change to the steps or outcomes of existing scenarios.

**Acceptance Scenarios**:

1. **Given** a product page, **When** it renders, **Then** the price and the primary action are the most
   prominent elements after the product name, and the primary action uses the single accent colour.
2. **Given** any page is waiting for data, **When** content is loading, **Then** a skeleton matching the
   shape of the expected content is shown and the layout does not jump when the content arrives.
3. **Given** the cart is empty, a search finds nothing, or a request fails, **When** the state is shown,
   **Then** it uses the branded empty or error treatment with a short message and one obvious next action.
4. **Given** the checkout flow, **When** the shopper moves through address, payment method, payment
   countdown and confirmation, **Then** buttons, fields, radio choices, status badges and the order summary
   share one consistent style, and validation errors are visually distinct and announced as before.
5. **Given** a confirmation dialog (e.g. removing a cart line), **When** it opens, **Then** it is styled in
   the same family and keeps its existing keyboard and focus behaviour.

---

### User Story 3 - Accessible in both themes and at every size (Priority: P3)

A shopper using a dark system theme, a 360 px phone, 200 % zoom, high-contrast (forced colours) mode, a
keyboard only, or a reduced-motion preference gets the same identity adapted to their needs: a warm dark
palette, readable contrast, no horizontal scrolling, a visible focus ring, and no unnecessary movement.

**Why this priority**: The current storefront already meets these baselines; the redesign must not regress
them. It is listed third because it constrains Stories 1–2 rather than adding new screens.

**Independent Test**: Run the automated accessibility checks and the visual baseline captures for key pages
in light and dark themes at 360, 768 and 1280 px, plus manual checks at 200 % zoom, forced colours and
reduced motion.

**Acceptance Scenarios**:

1. **Given** the system prefers a dark theme, **When** any page is shown, **Then** a warm dark palette is used
   and all text and controls meet WCAG 2.2 AA contrast.
2. **Given** a 360 px wide viewport or 200 % zoom, **When** any page is shown, **Then** there is no horizontal
   scrolling and no clipped content.
3. **Given** the shopper navigates by keyboard, **When** focus moves, **Then** a clearly visible focus indicator
   appears on every interactive element in both themes.
4. **Given** reduced motion is requested, **When** the shopper interacts, **Then** hover, press, skeleton and
   transition animations are suppressed.
5. **Given** forced-colours mode, **When** any page is shown, **Then** controls, borders, badges and the focus
   indicator remain distinguishable.

---

### User Story 4 - The operator console inherits the identity (Priority: P4)

An operator opening the order console sees the same palette, typefaces and controls as the storefront, so the
product feels like one system, while the console keeps its existing dense, task-oriented layout.

**Why this priority**: Consistency is valuable but the console is internal and low-traffic; a full redesign is
explicitly out of scope.

**Independent Test**: Open the console pages in both themes and confirm they use the shared palette,
typography and controls, keep their current layout, and pass existing console scenarios and accessibility
checks.

**Acceptance Scenarios**:

1. **Given** an operator opens the console, **When** it renders, **Then** it uses the shared colours, typefaces
   and control styles, with no changes to its layout, routes or behaviour.
2. **Given** the console's tables and status lists, **When** viewed, **Then** they remain at least as dense as
   today (no fewer rows visible at 1280 px) and meet AA contrast.

---

### Edge Cases

- Very long product names or category names: wrap gracefully (no overflow, no truncation of the price) on
  cards, product page, cart lines and order summary.
- Large prices and different currencies: amounts stay on one line and aligned in cart and summary.
- Missing, broken or oddly proportioned product images: shown in a consistent frame or the branded
  placeholder; no layout shift.
- Out-of-stock and low-availability products: status is conveyed by text and style, never colour alone.
- Empty catalogue, empty category, no search results, empty cart, no orders: branded empty states.
- Brand typefaces fail to load or load slowly: text is visible immediately in a fallback face of similar
  proportions, and the layout does not shift noticeably when the brand face arrives.
- A cart with 15 or more lines: summary and primary action remain reachable and readable at 360 px.
- The editorial introduction when the catalogue has fewer featured categories than designed for (zero, one
  or two): the section adapts or is omitted without empty gaps.
- Network-throttled sessions: the existing "slow down" notice keeps its prominence within the new palette.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The storefront MUST define a single, named set of design tokens covering colour roles (paper,
  surface, ink, muted ink, accent, accent contrast, border, success, warning, danger, focus), a type scale
  for display and body faces, spacing, corner radius, elevation and motion, with light and dark values.
- **FR-002**: Every shopper-facing page and shared component MUST take its colours, type sizes, spacing,
  radii, elevation and motion only from the design tokens; no component may carry literal values.
- **FR-003**: The store MUST present the Vibestore brand identity — wordmark in the header, tagline on the
  home page, brand name in the page title, brand icon in the browser tab, and a branded footer — replacing
  every user-visible "Storefront" label. Internal technical names (the web app's package, contract
  participant, CI workflow, documentation and test step wording) keep the name "storefront" and are out of
  scope.
- **FR-004**: Typography MUST pair a serif display face for headings and the wordmark with a highly readable
  body face, with a defined hierarchy (page title, section title, card title, body, caption, numerals for
  prices using equal-width figures).
- **FR-005**: All brand assets (typefaces, wordmark, icon, placeholder artwork) MUST be served from the
  store's own origin; the redesign MUST NOT add any third-party request and MUST NOT relax the existing
  content security policy.
- **FR-006**: Text MUST be visible immediately on first render using a fallback face of similar proportions
  while brand typefaces load; invisible text during font loading is not allowed.
- **FR-007**: The home page MUST open with an editorial introduction (tagline and links to up to four
  featured categories) built only from data the store already exposes, followed by the product listing.
- **FR-008**: Product cards and the product page MUST lead with imagery at a consistent aspect ratio and show
  a branded placeholder, with an accessible description, when an image is missing or fails.
- **FR-009**: The store MUST provide one consistent component family — primary, secondary and quiet buttons,
  text fields, selection controls, badges, dialogs, pager, notices and summaries — used everywhere those
  controls appear.
- **FR-010**: Loading states MUST use skeletons shaped like the expected content, and empty and error states
  MUST use the branded treatment with a short message and a single primary next action.
- **FR-011**: Interactive elements MUST give subtle hover and press feedback; all motion MUST be suppressed
  when the shopper requests reduced motion.
- **FR-012**: In both light and dark themes, all text MUST meet a contrast ratio of at least 4.5:1 (3:1 for
  large text), and all control boundaries, icons and focus indicators at least 3:1, against their
  backgrounds.
- **FR-013**: The redesign MUST preserve the existing baselines: 360 px minimum viewport with no horizontal
  scrolling, interactive targets of at least 44 × 44 px, an always-visible focus indicator, forced-colours
  support, and status never conveyed by colour alone.
- **FR-014**: The redesign MUST NOT change routes, page flows, form fields, validation rules, accessible names
  of controls or any shopper-visible behaviour; it is a presentation-only change.
- **FR-015**: Reference screenshots of key pages (home, category, product, cart, checkout, confirmation,
  order history, sign-in) MUST be captured at 360, 768 and 1280 px in both themes, and a change in their
  appearance MUST be detectable by the quality gate.
- **FR-016**: The operator console MUST use the shared design tokens, typefaces and control styles without
  changing its layout, routes, density or behaviour.

### Key Entities

- **Design token**: a named design decision (e.g. "accent", "space-4", "display-lg") with a light-theme and
  dark-theme value; the only source of visual values for every page and component.
- **Brand identity**: the store's name (Vibestore), wordmark, tagline ("Good things, good vibes"),
  brand icon, palette and typeface pairing; presented consistently across storefront and console.
- **Reference screenshot**: an approved image of a key page at a given width and theme, used to detect
  unintended visual change.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Automated accessibility checks report zero violations on every storefront and console page in
  both light and dark themes.
- **SC-002**: 100 % of existing shopper and operator acceptance scenarios pass with no changes to their steps.
- **SC-003**: The additional data a first-time visitor downloads on first page load, typefaces included, is
  at most 150 KB.
- **SC-004**: Time until text first appears on the home page is no slower than before the redesign, and
  visible layout shift during load stays below 0.1 on the standard layout-stability scale.
- **SC-005**: No page scrolls horizontally at 360 px width or at 200 % zoom.
- **SC-006**: 100 % of text and control colour pairs in both themes meet the contrast ratios in FR-012.
- **SC-007**: A search for user-visible occurrences of the old placeholder name "Storefront" returns zero
  results.
- **SC-008**: In a side-by-side review of before and after versions of the home, product and checkout pages,
  at least 4 of 5 reviewers rate the new design as more distinctive, and at least 4 of 5 as at least as
  trustworthy.

## Assumptions

- The brand name "Vibestore" and the tagline "Good things, good vibes" are decided; Vibestore is a fictional
  general-merchandise store, so the identity must suit any product category (the sample catalogue is
  outdoor gear). Changing the copy later is a copy-only change.
- Product imagery comes from the existing product image addresses; no photography is commissioned, and
  images of varying proportions are framed by the design.
- Featured categories on the home page are chosen from existing catalogue categories (e.g. the first few in
  catalogue order); no new curation or merchandising data is introduced.
- Typefaces used must carry a licence allowing self-hosting and redistribution in this open-source
  repository.
- Copy stays in English; apart from brand labels and empty/error message tone, wording is unchanged.
- The theme follows the shopper's system preference, as today; no manual theme switcher is added.
- Out of scope: new features (wishlists, reviews, promotions, recommendations), backend or contract changes,
  an operator console layout redesign, internationalisation, and marketing pages.

## Threat Model *(constitution Principle III)*

- **Assets**: the content security policy and the integrity of the page shell; shopper trust in what the
  page displays (prices, totals, payment state).
- **Actors**: shoppers, operators, and a hypothetical attacker able to influence third-party hosts or
  catalogue-provided content (product names, image addresses).
- **Trust boundaries**: the store's own origin vs. any external host; catalogue data (untrusted text and
  image addresses) vs. the presentation layer.
- **Abuse cases and mitigations**:
  - Supply-chain or tracking risk from externally hosted fonts or assets → FR-005 forbids third-party
    requests and CSP relaxation.
  - Injection through catalogue text rendered in new editorial sections → existing encoding rules apply; no
    raw markup rendering is introduced (FR-014).
  - Visual spoofing or misleading prominence (e.g. styling that hides a price change notice or payment
    decline) → FR-009/FR-010 keep notices and errors visually distinct; FR-015 reference screenshots catch
    regressions.
  - Hostile or oversized image addresses → images stay framed and fall back to the placeholder (FR-008);
    the policy on allowed image sources is unchanged.
- **Personal data**: no new personal data is collected, displayed or stored.

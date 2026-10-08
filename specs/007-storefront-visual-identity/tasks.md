---

description: "Task list for feature 007: Storefront Visual Identity (Vibestore)"
---

# Tasks: Storefront Visual Identity

**Input**: Design documents from `specs/007-storefront-visual-identity/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Constitution Principle V requires property, component and acceptance layers, and FR-015
requires visual baselines. Within each story, tests come before implementation.

**Organization**: tasks are grouped by user story (US1–US4 from the spec), so each story can be delivered and
checked on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: the user story the task belongs to (US1–US4)
- All paths are relative to the repository root. Commands run from `frontend/` unless they start with `./gradlew`.

## Global rules for every task (do not repeat per task)

- **Presentation only (FR-014).** Do not change routes, flows, form fields, validation, DOM roles, tab order or
  any existing accessible name or asserted string. The only text change is the brand label
  "Storefront" → "Vibestore". Internal names containing "storefront" stay: the package name, the Pact
  participant, `.github/workflows/storefront.yml`, docs and acceptance steps.
- **Tokens only.** Values come only from `frontend/src/ui/styles/tokens.css`, per
  [contracts/design-tokens.md](contracts/design-tokens.md). No literal colours or lengths in any other CSS. No
  `style=` attributes (the CSP has no `unsafe-inline`). No third-party URLs.
- **Accessibility baselines.** Targets ≥ `--control-min-size` (44 px). The focus ring is always visible. Status
  is never shown by colour alone. Motion only uses `--motion-duration` / `--motion-ease`, which are zeroed under
  reduced motion.
- **Surface contract.** The anatomy of each surface follows [contracts/ui-surfaces.md](contracts/ui-surfaces.md).
  Where this file and the contract disagree, the contract wins.
- **Green at checkpoints.** `npm run lint` and `npm run test` must be green at the end of every task group and at
  each checkpoint (`./gradlew -q verify` there too). A test-first task (marked "write first") may stay red until
  the implementation task that names it as "Makes … pass".

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: dependencies, the size baseline and the visual-runner scaffolding that every story builds on.

- [X] T001 Record the first-load size baseline before any change. On a clean checkout of `main`, run
  `npm ci && npm run build`. Write `frontend/scripts/check-bundle-budget.mjs` per [research §7](research.md): read
  `dist/index.html` and `dist/.vite/manifest.json` (enable `build.manifest: true` in `frontend/vite.config.ts`),
  collect the entry chunk, its static imports, the CSS and the `woff2` files, and sum the gzip size (level 9;
  fonts are counted raw). With `--record`, the script writes `frontend/budget.json`
  (`{"baselineBytes": <n>, "maxGrowthBytes": 153600}`). Without it, the script exits 1 when the current size
  exceeds `baselineBytes + maxGrowthBytes`, printing one line with both numbers. Add the npm script
  `"budget": "node scripts/check-bundle-budget.mjs"`. Commit `budget.json` with the baseline taken from `main`.
- [X] T002 [P] Add exact-pinned dependencies in `frontend/package.json` and `frontend/package-lock.json`:
  - runtime `@fontsource/fraunces` `5.3.0` and `@fontsource-variable/source-sans-3` `5.3.0`;
  - dev `@playwright/test` `1.63.0`, the same version as `playwright`. Do not use 1.64.0.

  Run `npm ci` and confirm osv-scanner reports nothing new (`.github/workflows/storefront.yml` step "Dependency
  scan").
- [ ] T003 [P] Scaffold the visual runner per [research §6](research.md) and
  [contracts/visual-baselines.md](contracts/visual-baselines.md):
  - `frontend/visual/playwright.config.ts`: `testDir: '.'` and `snapshotPathTemplate:
    '{testDir}/__screenshots__/{arg}{ext}'`. Six projects (`360|768|1280` × `light|dark`, height 900) set
    `colorScheme`, `reducedMotion: 'reduce'` and `expect.toHaveScreenshot: { maxDiffPixelRatio: 0.001,
    threshold: 0.2, animations: 'disabled', caret: 'hide' }`. Use `webServer` to start the T004 Vite server, and
    `reporter: 'line'`.
  - `frontend/scripts/visual.mjs`: run `podman` (fall back to `docker`) with
    `run --rm --ipc=host -e PLAYWRIGHT_IN_CONTAINER=1 -v <frontend>:/work -w /work
    mcr.microsoft.com/playwright:v1.63.0-noble npx playwright test -c visual/playwright.config.ts
    <forwarded args>`. Print only failures and diff paths.
  - npm script `"visual": "node scripts/visual.mjs"`.
  - `frontend/visual/guard.ts`, imported by every spec: it throws unless `PLAYWRIGHT_IN_CONTAINER=1`.

  Add `visual/` to the `tests/**` lint override in `frontend/eslint.config.js` and keep it out of
  `vitest.config.ts` `include`.
- [ ] T004 Build the MSW-backed visual harness (depends on T002 and T003):
  - `frontend/visual/vite.visual.config.ts` extends `frontend/vite.config.ts`. It sets `publicDir` to
    `frontend/visual/public/`, so the worker never reaches the production `dist/`. It serves on
    `http://localhost:80` (inside the container), so the absolute `http://localhost` base of the handlers in
    `frontend/tests/msw/*.ts` matches without editing them. Its `root` entry is `frontend/visual/harness/index.html`.
  - Generate `frontend/visual/public/mockServiceWorker.js` with `npx msw init visual/public --save=false`.
  - Write `frontend/visual/harness/main.tsx`. It starts `setupWorker(...handlers)` from `msw/browser`, in the same
    order as `frontend/tests/msw/server.ts`, then mounts the real app router from `frontend/src/main.tsx` (export
    a `mountApp()` from `src/main.tsx` if needed; do not duplicate the app).
  - Write `frontend/visual/harness/scenarios.ts` to seed deterministic state from a `?scenario=` query:
    - `anonymous`: the catalogue only.
    - `shopper`: signed in as `ANA` (`frontend/tests/msw/identity.ts`), cart with two lines (rake × 1,
      lantern × 2), one saved address, and `orderServer` seeded with three orders in mixed statuses plus one
      placed order for the confirmation page, with payment attempts in `paymentServer`.
  - Rewrite every fixture image URL `https://cdn.example.test/<id>.jpg` to a same-origin
    `/visual-fixtures/<id>.jpg`. Commit five small neutral JPEGs (≤ 20 KB each) under
    `frontend/visual/public/visual-fixtures/`. `hoe` has no image (`image: false`), so the placeholder is covered.

  Smoke check: `npm run visual -- --list` lists the specs and the server starts.

**Checkpoint**: the dependencies are installed, `npm run budget` passes against the baseline, and the visual
runner starts inside the container.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the new token set, fonts, enforcement and contrast proof. Every story's restyling depends on these.

**⚠️ CRITICAL**: no user-story styling starts before this phase is complete.

- [X] T005 Write the contrast test first, in `frontend/tests/styles/wcag.ts` and
  `frontend/tests/styles/contrast.test.ts`, per [research §5](research.md):
  - `wcag.ts` holds the pure `relativeLuminance(hex)` and `contrastRatio(a, b)` (WCAG 2.x formula).
  - fast-check properties: symmetry, a range of `[1, 21]`, `#FFFFFF`/`#000000` = 21, and `ratio(a, a)` = 1.
  - The test parses `frontend/src/ui/styles/tokens.css`. The light values come from `:root`. The dark values
    come from the `@media (prefers-color-scheme: dark)` block, and every colour token must have one.
  - It asserts every row of the "Contrast matrix" table in
    [contracts/design-tokens.md](contracts/design-tokens.md), copying the pairs into a typed array in the test:
    text pairs ≥ 4.5 and ui pairs ≥ 3, in both themes.
  - The test must fail against the current tokens, where `--color-border-strong` is missing.
- [X] T006 Rebuild `frontend/src/ui/styles/tokens.css` exactly per
  [contracts/design-tokens.md](contracts/design-tokens.md):
  - Keep every existing name (`--color-bg` … `--motion-duration`). Add every token marked **new**: colours,
    `--font-display`, `--font-body`, `--font-size-3xl`, `--font-size-display`, `--line-height-tight`,
    `--letter-spacing-display`, `--font-numeric`, `--space-10`, `--space-12`, `--radius-lg`, `--radius-pill`,
    `--border-width`, `--border-width-strong`, `--image-ratio-card`, `--image-ratio-detail`, `--layout-measure`,
    `--motion-ease`, `--shadow-md`.
  - Use the light and dark hex values from the table. `--font-family` becomes an alias of `--font-body`.
  - Make `--focus-ring` use the new blue `--color-focus`.
  - Add the length tokens the T008 migration needs (e.g. `--size-icon`, `--size-badge`, `--header-min-height`,
    `--card-min-width`), each named for its role.
  - Keep `@media (prefers-reduced-motion: reduce)` and the 768 px gutter rule.

  T005 must now pass.
- [X] T007 [P] Write `frontend/src/ui/styles/fonts.css` per [research §1–§2](research.md):
  - One `@font-face` for `Fraunces` (weight 600, `font-display: swap`) and one for `Source Sans 3` (weight
    `200 900`, `font-display: swap`). Each has a woff2-only `src` that imports the package files
    `@fontsource/fraunces/files/fraunces-latin-600-normal.woff2` and
    `@fontsource-variable/source-sans-3/files/source-sans-3-latin-wght-normal.woff2`, so Vite emits hashed
    `/assets/*.woff2`. Copy the latin `unicode-range` from the package CSS.
  - The fallback faces `Fraunces Fallback` (`local(Georgia), local('Times New Roman')`, size-adjust 104 %, ascent
    88 %, descent 23 %, line-gap 0 %) and `Source Sans 3 Fallback` (`local(Arial), local(Helvetica)`, 99 %, 100 %,
    28 %, 0 %).
  - Insert each fallback into the stacks of `--font-display` and `--font-body` in `tokens.css`, right after the
    brand face.
  - Import `fonts.css` once in `frontend/src/main.tsx`, before `global.css`.

  Check: `npm run build` emits exactly two `.woff2` files and no `.woff`. `npm run budget` passes.
- [X] T008 Migrate the 60 literal values (59 lengths, 1 colour function) out of every
  `frontend/src/ui/**/*.module.css` and `frontend/src/ui/styles/global.css` into tokens from T006. This is a
  mechanical swap with no visual intent yet. Then update `global.css`:
  - `body` uses `var(--font-body)`;
  - `h1, h2, h3` use `var(--font-display)`, `var(--font-weight-bold)`, `var(--line-height-tight)` and
    `var(--letter-spacing-display)`;
  - `a` hover uses `--color-primary-hover`;
  - add `.tabular { font-variant-numeric: var(--font-numeric); }`.
- [X] T009 Write `frontend/scripts/check-design-tokens.mjs` per [research §4](research.md) and run it first in
  `npm run lint` (`frontend/package.json`):
  - Scan `src/**/*.module.css` and `src/ui/styles/global.css`.
  - Fail on colour literals (hex, `rgb/rgba/hsl/hsla/oklch/lab/color()`, named colours except
    `transparent|currentColor|inherit`), raw lengths (`px|rem|em|vh|vw|ch`) outside `var(--…)`, and any
    `http:`/`https:` URL.
  - Allow `0`, percentages, `fr`, unitless line-heights, `@media` preludes and the whole of `tokens.css` and
    `fonts.css`.
  - Output one line per offence (`file:line value`) and exit 1.

  Add `frontend/tests/styles/checkDesignTokens.test.ts`, which runs the checker over temp fixture files: one
  clean file and one per offence kind. Depends on T008, after which lint is green.

**Checkpoint**: `./gradlew -q verify` is silent. The storefront looks like today but runs on the new tokens and
fonts, which is the base for every story.

---

## Phase 3: User Story 1 - A recognisable, editorial storefront while browsing (Priority: P1) 🎯 MVP

**Goal**: the Vibestore wordmark, tagline and footer; an editorial home with up to four featured categories;
image-led cards with a branded placeholder; one shared look across home, category and search.

**Independent Test**: open `/`, a category and `/search?q=a` at 360 and 1280 px. The brand header and footer, the
editorial introduction, the type pairing, the palette and the image-led cards are all present. The browse
scenarios in `frontend/acceptance/features/catalogue-browsing.feature` pass unchanged.

### Tests for User Story 1 ⚠️ (write first, see them fail)

- [ ] T010 [P] [US1] Property tests for `frontend/src/app/catalog/featured.ts` in
  `frontend/tests/app/featured.test.ts` (fast-check over arbitrary category lists). The result:
  - has at most 4 entries;
  - contains only `status === 'active'` categories without a parent;
  - keeps catalogue order;
  - is empty when none qualify;
  - is a stable subsequence of its input.
- [ ] T011 [P] [US1] Update `frontend/tests/ui/layout.test.tsx`:
  - line 73 `name: 'Storefront'` → `'Vibestore'`;
  - add assertions: the banner has a link named "Vibestore" to `/`, the brand mark is `aria-hidden`, and the
    contentinfo contains the tagline "Good things, good vibes".
- [ ] T012 [P] [US1] Extend `frontend/tests/ui/browse.test.tsx`:
  - the home page shows the tagline paragraph and featured-category links named after the `gardenTools` and
    `lighting` fixtures (`frontend/tests/msw/catalog.ts`);
  - the section is absent when the categories handler returns an empty list (`server.use`);
  - with one and with two qualifying categories, the tiles fill the row with no empty placeholder tiles
    (assert the number of tile links equals the number of categories);
  - the h1 is still "Products";
  - a product card is a single link named after the product;
  - `hoe` (no image) shows the placeholder `role="img"` named "Hoe (no image available)" (use the existing
    `ProductImage` naming), and a broken image (fire `error`) switches to the same placeholder;
  - availability is text ("In stock" / "Out of stock", the existing strings).
- [ ] T013 [P] [US1] Write the US1 behaviour scenarios first (constitution §V), in a new
  `frontend/acceptance/features/visual-identity.feature` with steps in a new
  `frontend/acceptance/steps/identity-visual.steps.ts`. The steps bind through roles and accessible names; the
  feature text names no selectors or endpoints. Scenarios:
  - "A visitor recognises the store": the header shows the Vibestore name linking home, and the tab title
    names Vibestore.
  - "The home page invites browsing by category": the home page shows the tagline "Good things, good vibes";
    choosing a featured category opens that category's products.
  - "A product without a photograph still looks deliberate": a product listed without an image shows the
    "no image available" placeholder.

  Seeding uses `frontend/acceptance/support/catalogue.ts`. Add an `{ image: false }` option to
  `createProduct` that skips the image `#post`. Existing scenarios and steps stay untouched (SC-002).

### Implementation for User Story 1

- [ ] T014 [P] [US1] Create the brand module `frontend/src/ui/brand/`:
  - `brand.ts` exports `BRAND_NAME = 'Vibestore'` and `BRAND_TAGLINE = 'Good things, good vibes'`.
  - `BrandMark.tsx` is a static inline SVG (≤ 1 KB, `aria-hidden="true"`, `focusable="false"`, fills through
    `currentColor` and CSS classes, `forced-color-adjust: none` only on the mark).
  - `Wordmark.tsx` renders the mark plus the name in `--font-display` 600 at `--font-size-xl`.
  - `brand.module.css` holds their styles.
- [ ] T015 [P] [US1] Add `frontend/public/favicon.svg` (the same mark, with a `prefers-color-scheme` media query
  inside the SVG). Update `frontend/index.html`: `<title>Vibestore — Good things, good vibes</title>` and
  `<link rel="icon" type="image/svg+xml" href="/favicon.svg">`. Check that the production `platform/docker/Dockerfile.storefront`
  copies `dist/` including `favicon.svg`; Vite copies `public/` into `dist/`.
- [ ] T016 [US1] Restyle the shell in `frontend/src/ui/components/Layout.tsx` and `Layout.module.css`, per the
  "Shell" table of [ui-surfaces.md](contracts/ui-surfaces.md):
  - A paper header with a bottom rule. `Wordmark` replaces the "Storefront" text inside the existing brand
    `Link`, whose accessible name becomes "Vibestore".
  - The nav keeps its links, order and names, and wraps to two rows at 360 px without overflow.
  - The footer is a `--color-surface` band with the wordmark, the tagline as a `<p>` and a copyright line.
  - The skip link stays first.

  Depends on T014. Makes T011 pass.
- [ ] T017 [P] [US1] Implement `frontend/src/app/catalog/featured.ts`:
  `selectFeaturedCategories(categories): readonly Category[]`, a pure function returning the first ≤ 4 active
  top-level categories in catalogue order. Makes T010 pass and keeps Stryker ≥ 80 % on the file.
- [ ] T018 [US1] Add the editorial introduction to `frontend/src/ui/pages/HomePage.tsx`, with a new
  `frontend/src/ui/pages/home.module.css`, per the "Home (editorial)" table:
  - The h1 "Products" stays.
  - A lead `<p>` with the tagline in `--font-size-display` (not a heading).
  - Featured-category tiles: links to `/categories/:id` named after the category, using `--color-surface`,
    `--radius-lg` and `--space-10` rhythm. The data comes from `useCategories` + `selectFeaturedCategories`.
  - The block is omitted when the selection is empty or the categories query fails, so the tagline still shows.
  - With one to three tiles, the tile grid uses `repeat(auto-fit, minmax(…, 1fr))` (token-based minimum) so the
    tiles stretch across the row and leave no empty cells (spec edge case).
  - The product listing comes after the block.

  Depends on T017. Makes the home part of T012 pass.
- [ ] T019 [P] [US1] Restyle `frontend/src/ui/components/ProductImage.tsx` and `states.module.css` (image and
  placeholder rules):
  - A fixed `aspect-ratio: var(--image-ratio-card)` frame with `object-fit: cover`, `--radius-md`, and a
    `--color-skeleton` fill while loading.
  - The placeholder fills the same frame with the brand mark (`aria-hidden`) and the text "No image" on
    `--color-surface`. The existing `role="img"` and `aria-label` stay.
  - Accept a `ratio?: 'card' | 'detail'` prop (default `card`), with `detail` using `--image-ratio-detail`.
- [ ] T020 [P] [US1] Restyle `frontend/src/ui/components/ProductCard.tsx` and `ProductCard.module.css`, per the
  "Product card" table:
  - Image first, then the name (display 600, `--font-size-lg`, two-line clamp), the price (`Money` with
    `.tabular`, 600) and the availability text, with colour only as reinforcement.
  - The whole card is a single link: the existing link stretches over the card with a `::after` overlay, so there
    is one tab stop and the name is unchanged.
  - Hover and press: image scale ≤ 1.02 and `--shadow-md`, with no motion under reduced motion.
- [ ] T021 [P] [US1] Restyle `frontend/src/ui/components/ProductGrid.module.css` (2 / 3 / 4 columns at 360 / 768 /
  1280 with `--space-5` gaps), `CategoryNav.module.css` (quiet links, `aria-current` gets a 2 px primary
  underline) and `SearchBox.module.css` (field per the US2 field contract: `--color-surface-raised`,
  `--color-border-strong`, `--radius-md`).
- [ ] T022 [US1] Align `frontend/src/ui/pages/browse.module.css` and `pages.module.css` (page title: display,
  `--font-size-3xl`, `--font-size-2xl` at ≤ 640 px; section rhythm `--space-8`/`--space-10`) so
  `CategoryPage.tsx` and `SearchPage.tsx` share the home treatment. Depends on T018 and T020. T012 is fully green.

**Checkpoint**: US1 is shippable on its own. `npm run test`, `npm run lint` and `npm run budget` pass, and the
browse acceptance features are unchanged.

---

## Phase 4: User Story 2 - Product, cart and checkout feel polished and trustworthy (Priority: P2)

**Goal**: one component family across product, cart, checkout and orders; skeleton loading; branded empty and
error states.

**Independent Test**: walk a product from the product page to confirmation (including a declined payment and an
empty cart) with `npm run dev`. Every control and state uses the family. `frontend/tests/ui/{cart,checkout,
account,states}.test.tsx` pass, and the `shopping-cart`, `checkout` and `order-tracking` features pass unchanged.

### Tests for User Story 2 ⚠️

- [ ] T023 [P] [US2] Extend `frontend/tests/ui/states.test.tsx`:
  - `Loading` with each `variant` (`spinner`, `grid`, `detail`, `lines`) keeps `role="status"`,
    `aria-live="polite"` and the accessible text "Loading…" (or the given label), and its skeleton blocks are
    `aria-hidden`.
  - `Empty` renders one heading, one message and one primary action link.
  - `ErrorState` keeps its existing asserted strings and adds the brand-voice sentence beside them.
- [ ] T024 [P] [US2] Add a regression assertion to `frontend/tests/ui/checkout.test.tsx`: the selected payment
  radio card still exposes a checked native radio (`getByRole('radio', { checked: true })`), so selection is not
  carried by colour alone. Do not change any existing step.
- [ ] T025 [US2] Append the US2 scenarios to `frontend/acceptance/features/visual-identity.feature` (steps in
  `identity-visual.steps.ts`, reusing the cart and checkout steps where they already exist):
  - "An empty cart points the way back": an empty cart shows a friendly message and a single action that leads
    to the products.
  - "A missing page offers a way home": an unknown address shows "404" and an action back to the store.

  Depends on T013.

### Implementation for User Story 2

- [ ] T026 [US2] Build the button family in `frontend/src/ui/components/buttons.module.css`, per the "Buttons"
  row: `primary` (primary fill, primary-contrast text, hover `--color-primary-hover`, pressed `translateY(1px)`),
  `secondary` (`--border-width` `--color-border-strong`) and `quiet` (primary text, underline on hover). The
  disabled state is muted on surface with `not-allowed`, and every variant is at least 44 px. Keep the existing
  class names that components import. If one is renamed, update every importer in the same task.
- [ ] T027 [P] [US2] Build the field family in `frontend/src/ui/components/forms.module.css` (used by
  `TextField.tsx`, `QuantityInput.tsx`, `AddressForm.tsx`, `PreferencesForm.tsx`): a raised surface,
  `--color-border-strong` 1 px, `--radius-md`, and a 600 label above. In the error state, the text and a 2 px
  border use `--color-danger`. The message stays linked by `aria-describedby`.
- [ ] T028 [P] [US2] Turn `frontend/src/ui/components/PaymentMethodPicker.tsx` and `AddressPicker.tsx` into
  radio cards, styled in `forms.module.css`:
  - Unselected: a 1 px `--color-border-strong` border.
  - Selected: a 2 px `--color-primary` border and an `--color-accent-subtle` fill, with the native radio mark
    still visible.
  - The `:focus-visible` ring is drawn on the card.

  The markup keeps every `input type="radio"` and label. Makes T024 pass.
- [ ] T029 [P] [US2] Restyle `frontend/src/ui/components/StatusBadges.tsx` (`--radius-pill`, accent-subtle
  fill, 1 px border kept for forced colours, text "Order: …"/"Payment: …" unchanged), `ConfirmDialog.module.css`
  (raised surface, `--radius-lg`, `--shadow-md`, backdrop ink at 50 % through a token, focus trap unchanged) and
  `Pager.module.css` (secondary buttons; current page `aria-current` plus a 2 px primary underline).
- [ ] T030 [P] [US2] Restyle the notices:
  - `frontend/src/ui/components/PriceChangeNotice.tsx`: `role="alert"` kept, `--color-notice-bg/text`, a 2 px
    `--color-warning` left rule, and the accept button as primary.
  - The throttle notice `.notice` in `Layout.module.css` and `Throttled.tsx`: `role="status"` kept, notice
    colours, a 1 px `--color-border-strong` border, and a tabular countdown.
  - `PaymentCountdown.tsx`: notice styling, with the text still carrying the time.

  The price-change and decline notices must stay the most prominent element in their region (threat model).
- [ ] T031 [P] [US2] Restyle the cart and summary in `frontend/src/ui/components/cart.module.css`,
  `CartLine.tsx` and `OrderSummary.tsx`:
  - Lines show the thumbnail (`ProductImage`), the name, a tabular price and quantity, and a quiet remove button.
  - The summary is a `--color-surface` card with tabular totals and the display serif on the total.
  - A 15-line cart keeps its summary and primary action reachable at 360 px: the summary stacks under the lines,
    with no sticky overlap.
- [ ] T032 [P] [US2] Restyle the orders surfaces: `frontend/src/ui/components/orders.module.css`, `OrderRow.tsx`,
  `StatusHistory.tsx`, and the pages `OrdersPage.tsx`, `OrderPage.tsx` and `ConfirmationPage.tsx`. The
  confirmation gets a display heading and a summary card. Status text stays the carrier of meaning.
- [ ] T033 [US2] Restructure the product page in `frontend/src/ui/pages/ProductPage.tsx`, adding
  `frontend/src/ui/pages/product.module.css`. Top to bottom: back link, `ProductImage ratio="detail"` (beside the
  text from 768 px, above it below that), h1 (display), large tabular price, availability text, quantity and
  "Add to cart" (primary), then the description at `--layout-measure`. Names and order of controls in the DOM
  are unchanged. Depends on T019 and T026.
- [ ] T034 [US2] Add skeleton variants to `frontend/src/ui/components/Loading.tsx` and `states.module.css`, per
  [research §8](research.md):
  - The `variant?: 'spinner' | 'grid' | 'detail' | 'lines'` prop, `spinner` by default.
  - Skeleton blocks are `aria-hidden`, use `--color-skeleton`, and reserve dimensions with `aspect-ratio` and
    token min-heights.
  - The shimmer only runs under `prefers-reduced-motion: no-preference`. Under forced colours, blocks get a
    `GrayText` outline.
  - The label is visually hidden but kept in the `role="status"` element.

  Thread a `loading` variant prop through `QueryBoundary.tsx` and `ProductListing.tsx`: listings use `grid`, the
  product page `detail`, and orders and account pages `lines`. Makes T023 (Loading) pass.
- [ ] T035 [US2] Brand the empty and error states in `frontend/src/ui/components/Empty.tsx`, `ErrorState.tsx`,
  `ActionError.tsx` and `frontend/src/ui/pages/NotFoundPage.tsx`, per the "States" table:
  - A centred mark, one heading, one message and one primary action.
  - Keep every existing heading, label and message string, adding the brand-voice sentence beside them.
  - The not-found page shows a display "404".

  Makes T023 fully green.
- [ ] T036 [US2] Apply the field and button families to the identity and account pages (`SignInPage`,
  `RegisterPage`, `ForgotPasswordPage`, `ResetPasswordPage`, `VerifyEmailPage`, `AccountPage`, `AddressesPage`,
  `NotificationsPage`, `CheckoutPage`, `NotAllowedPage` and `PlaceholderPage` in `frontend/src/ui/pages/`), using
  only class changes in `pages.module.css`. Form widths are capped at `--layout-measure`. Depends on T026 and
  T027.

**Checkpoint**: US1 and US2 both work. Every Vitest suite is green and the transaction acceptance features are
unchanged.

---

## Phase 5: User Story 3 - Accessible in both themes and at every size (Priority: P3)

**Goal**: prove and fix accessibility for the new identity: dark theme, AA contrast, 360 px and 200 % zoom,
forced colours, keyboard focus, reduced motion and layout stability.

**Independent Test**: `npm run visual -- --grep @quality` passes in the container. Manual checks follow
[quickstart.md §4–§5](quickstart.md).

### Tests for User Story 3 ⚠️

- [ ] T037 [US3] Write `frontend/visual/quality.spec.ts` (tagged `@quality`) per the "Non-screenshot assertions"
  table of [visual-baselines.md](contracts/visual-baselines.md). The cases cover the 8 pages (home, category
  `/categories/<gardenTools.id>`, product `/products/<rake.id>`, cart, checkout, confirmation, orders, sign-in;
  scenarios from T004), and the console pages `/console/orders` and `/console/stock` for axe:
  - no horizontal overflow (`scrollWidth <= clientWidth`) at 360 and 640 px;
  - `@axe-core/playwright` WCAG 2.2 A/AA with zero violations in light and dark;
  - a forced-colours smoke test (`forcedColors: 'active'`) on home and checkout: the primary button, links, the
    focus ring and the selected radio stay visible;
  - CLS < 0.1 on home and product at 360 and 1280. A `PerformanceObserver` on `layout-shift` is installed with
    `addInitScript` and read after `document.fonts.ready` plus 500 ms;
  - controls ≥ 44 px on checkout at 360;
  - no request to an origin other than the app origin on home and product at 1280;
  - under reduced motion, `document.getAnimations()` is empty after load;
  - first text is not held back by the brand fonts (SC-004): on home and product at 360, delay every
    `/assets/*.woff2` response by 3 s with `page.route`; `first-contentful-paint` (from
    `performance.getEntriesByType('paint')`) must occur before any font response completes, and visible text
    must be present in the DOM before then.

  Run it and record the failures it finds. They are the input for T039–T041.
- [ ] T038 [US3] Append the US3 scenarios to `frontend/acceptance/features/visual-identity.feature`:
  - "The store is usable in a dark appearance": with a dark appearance preferred, the home and checkout pages
    pass the accessibility audit. Add a `colorScheme` option to `#contextOptions()` in
    `frontend/acceptance/support/world.ts`, set by a Given step; the default stays light.
  - "Nothing scrolls sideways on a small phone": on the mobile viewport, home, product, cart and checkout never
    scroll sideways.

  Depends on T013.

### Implementation for User Story 3

- [ ] T039 [P] [US3] Add `@media (forced-colors: active)` rules per the "Forced-colours mapping" of
  [design-tokens.md](contracts/design-tokens.md) to `buttons.module.css`, `forms.module.css` (radio cards: a
  `Highlight` 2 px outline when selected), `StatusBadges` (border kept), `Pager.module.css`,
  `ProductCard.module.css` (no shadow), `states.module.css` (skeleton outline) and `Layout.module.css` (notice
  border).
- [ ] T040 [P] [US3] Fix every overflow and contrast failure that T037 reported, at 360 / 640 px and in both
  themes. The usual causes are long names (wrap with `overflow-wrap: anywhere`), long prices (`white-space:
  nowrap` on amounts only) and the header nav. The fix is CSS only, in the module that owns the element.
- [ ] T041 [US3] Tune the fallback metrics in `frontend/src/ui/styles/fonts.css`
  (`size-adjust`/`ascent-override`/`descent-override`) until CLS < 0.1 holds on home and product, with margin.
  Verify by blocking `/assets/*.woff2` in the T037 run (route abort): text must stay visible and the layout must
  stay intact. Depends on T037.

**Checkpoint**: the `@quality` suite is green in the container, and the acceptance axe audits (light) stay green.

---

## Phase 6: User Story 4 - The operator console inherits the identity (Priority: P4)

**Goal**: the console uses the shared tokens, typefaces and controls without any layout or density change.

**Independent Test**: `frontend/tests/ui/console.test.tsx` passes unchanged. The console pages pass axe in both
themes (T037), and `frontend/acceptance/features/console.feature` passes unchanged. At 1280 px, the orders table
shows at least as many rows as before (compare against a screenshot taken before the change).

- [ ] T042 [US4] Append the US4 scenario to `frontend/acceptance/features/visual-identity.feature`:
  "Operators work under the same brand". The console shows the Vibestore name beside "Console", and the orders
  list is still offered. Reuse the operator sign-in from `frontend/acceptance/support/operator.ts`. Depends on
  T013.
- [ ] T043 [P] [US4] Migrate `frontend/src/ui/console/console.module.css` to semantic tokens only:
  - colours, `--radius-*`, the body in `--font-body` and the headings in `--font-display`;
  - the table header on `--color-surface` and row borders in `--color-border`;
  - the button and field classes from `buttons.module.css` and `forms.module.css` (`TransitionButtons.tsx`,
    `StockAdjustmentForm.tsx`).

  Do not change padding, row height, column order or control sizes beyond the token swap. Take a 1280 px
  screenshot of `/console/orders` before and after and attach both to the PR.
- [ ] T044 [US4] Show the brand in `frontend/src/ui/console/ConsoleLayout.tsx`: a small `Wordmark` with the
  existing "Console" text. The nav `aria-label="Console"` and every role stay. Depends on T014 and T043.

**Checkpoint**: all four stories are complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T045 Write `frontend/visual/pages.spec.ts`: 48 `toHaveScreenshot('<page>-<width>-<theme>.png', { fullPage:
  true })` captures (8 pages × 6 projects) per [visual-baselines.md](contracts/visual-baselines.md):
  - clock frozen with `page.clock.install({ time: '2026-01-15T10:00:00Z' })`;
  - `await document.fonts.ready` before each capture;
  - the scenarios from T004.

  Generate the baselines with `npm run visual -- --update-snapshots` (the container only) and commit
  `frontend/visual/__screenshots__/*.png`. Re-run without the flag: 48 pass.
- [ ] T046 [P] Add a "Visual regression" step to `.github/workflows/storefront.yml`, after "Unit and component
  tests". It runs `npm run visual` inside `mcr.microsoft.com/playwright:v1.63.0-noble` through the runner's
  Docker socket, using the same image-pin comment block as the other tool containers. On failure it adds
  `frontend/visual/test-results/` to the report tarball. Also add a "Bundle budget" step: `npm run build && npm
  run budget`. Keep the workflow's path filters, and update `.github/scripts/tests/test-path-filter-check.sh` if
  `frontend/visual/**` needs listing.
- [ ] T047 [P] Document the design system in `frontend/README.md`, under a "Design system" section:
  - the token layers and where values live;
  - the rule "no literal values" enforced by `check-design-tokens.mjs`;
  - the fonts and their licences (OFL, with the Fontsource packages);
  - how to run and update visual baselines (container only, PNGs reviewed in the PR);
  - the budget check.

  Link [contracts/design-tokens.md](contracts/design-tokens.md). Update `docs/build.md` with the `visual` and
  `budget` scripts.
- [ ] T048 [P] SC-007 sweep: `grep -rn "Storefront" frontend/src frontend/index.html frontend/public` returns
  nothing user-visible. Comments and internal identifiers that match "storefront" are allowed. Fix any remaining
  visible label.
- [ ] T049 Run the full gate in the foreground, in this order:
  1. `./gradlew -q verify` (silent);
  2. `npm run build && npm run budget` (growth ≤ 150 KB);
  3. `npm run visual` (48 + quality, green);
  4. with the platform up (`scripts/dev-env.sh init`), `STOREFRONT_URL=http://localhost:8080 npm run acceptance`.
     100 % must pass with no step edits (SC-002).

  Fix failures in the owning module, never by editing acceptance steps or regenerating baselines to hide a
  regression.
- [ ] T050 Run the [quickstart.md](quickstart.md) walkthrough end to end, including §5 (font-load failure). Capture
  before and after screenshots of home, product and checkout (`main` vs branch) at 1280 light, and attach them
  to the PR for the SC-008 review.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: T001 runs first, on `main`, before anything changes the bundle. T002 and T003 run in
  parallel. T004 needs T002 and T003.
- **Foundational (Phase 2)**: needs T002 (fonts). T005 → T006, and T007 can run beside T006. T008 needs T006, and
  T009 needs T008. This phase blocks every story.
- **US1 (Phase 3)**: needs Phase 2. It is the MVP.
- **US2 (Phase 4)**: needs Phase 2. It is independent of US1 except that T033 uses `ProductImage` from T019 (do
  T019 first, or include it in the US2 work package).
- **US3 (Phase 5)**: needs T004, plus US1 and US2 surfaces to audit. T037 can be written earlier and run
  repeatedly.
- **US4 (Phase 6)**: needs Phase 2 and T014 (Wordmark).
- **Polish (Phase 7)**: T045 baselines only after US1–US4 are final. T049 and T050 come last.

### Story dependency graph

```text
T001 ─┐
T002 ─┼─► T004 (visual harness) ───────────────────────────────┐
T003 ─┘                                                         │
T002 ─► Phase 2 (T005→T006→T008→T009, T007) ─┬─► US1 (MVP) ─┐   │
                                            ├─► US2 ────────┼───┴─► US3 ─► Polish (T045→T049→T050)
                                            └─► US4 ────────┘
```

### Within each story

Tests first and failing, then pure logic (`featured.ts`), then components, then pages. Commit after each task or
logical group.

## Parallel Example

```text
# Phase 1 (after T001):  T002 ∥ T003
# Phase 2:               T005 → T006 ∥ T007 → T008 → T009
# US1:                   T010 ∥ T011 ∥ T012 ∥ T013 (tests) → T014 ∥ T015 ∥ T017 ∥ T019 ∥ T020 ∥ T021 → T016, T018 → T022
# US2:                   T023 ∥ T024 ∥ T025 → T026 → T027 ∥ T028 ∥ T029 ∥ T030 ∥ T031 ∥ T032 → T033, T034, T035, T036
# US3:                   T037 ∥ T038 → T039 ∥ T040 → T041
# US4:                   T042 → T043 → T044
# Polish:                T046 ∥ T047 ∥ T048, then T045 → T049 → T050
```

## Implementation Strategy

### Work packages for parallel subagents (per the plan's Execution Strategy)

At most 3 worktree builds at once. Each agent starts with `git merge --ff-only` from the feature branch and
merges back `--no-ff` in this order.

| Package | Tasks | Model / effort |
|---------|-------|----------------|
| WP-0 Baseline | T001 | Haiku / low (run on `main` first) |
| WP-1 Tokens & fonts | T002, T005–T009 | Sonnet / medium |
| WP-2 Visual harness | T003, T004, T037 (spec only) | Opus / high |
| WP-3 Browse (US1) | T010–T022 | Sonnet / medium (after WP-1) |
| WP-4 Transactions (US2) | T023–T036 | Sonnet / medium (after WP-1; takes T019 if WP-3 has not merged) |
| WP-5 Console & docs | T042–T044, T046–T048 | Sonnet / low (after WP-3) |
| WP-6 Accessibility & gate | T038–T041, T045, T049, T050 | Opus / high, run by the orchestrator in the foreground |

### MVP first (User Story 1 only)

1. Phase 1 and Phase 2.
2. Phase 3 (US1).
3. **Stop and validate**: the US1 independent test, `./gradlew -q verify`, `npm run budget` and the browse
   acceptance features. The store already reads as Vibestore.

### Incremental delivery

US1 (brand and browse) → US2 (transactions) → US3 (accessibility proof) → US4 (console) → Polish (baselines,
CI, docs). Each step leaves the gate green and the acceptance suite unchanged.

## Notes

- [P] means different files and no dependency on an incomplete task.
- Baselines are regenerated only inside the container, and only in commits that intend a visual change.
- Avoid adding sentences to strings that tests assert. Add copy beside them instead.

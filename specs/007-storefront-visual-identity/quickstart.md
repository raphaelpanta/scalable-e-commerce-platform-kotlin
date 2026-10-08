# Quickstart: Validate the Vibestore Visual Identity

This is a validation guide: it proves the redesign described in [spec.md](./spec.md) works, from the automated gates
(sections 2 and 3) to a manual walkthrough per user story (section 4) and the regression and review checks
(sections 5 to 7). It is a presentation-only change, so nothing here touches routes, flows or contracts. Platform facts
(seed data, payment tokens) come from feature 005's [quickstart](../005-storefront-dev-bootstrap/quickstart.md).

Run every command from the repository root unless stated otherwise.

## 1. Prerequisites

| Prerequisite | Expected |
|--------------|----------|
| Node and npm | the version in `frontend/.nvmrc` (Node 24), e.g. `nvm use` inside `frontend/` |
| Podman or Docker | reachable; used only to run the Playwright Linux image `mcr.microsoft.com/playwright:v1.63.0-noble` (pulled on first `npm run visual`) |
| Running platform | only for section 4 and the acceptance run in section 6 |

Bring the platform up with `scripts/dev-env.sh init --start` (see feature 005). The gateway serves the storefront at
`http://localhost:8080/`. On Podman keep `BUILDAH_FORMAT=docker` set, as the script does for you.

## 2. Static gates

```bash
cd frontend && npm ci
```

```bash
npm run lint
```

`lint` now also runs `scripts/check-design-tokens.mjs` (added by this feature): it fails on any literal colour or size
outside `src/ui/styles/tokens.css` (FR-002).

```bash
npm run test
```

Includes the contrast test of every text and control role pair in both themes (FR-012, SC-006).

```bash
npm run build
```

```bash
node scripts/check-bundle-budget.mjs
```

`check-bundle-budget.mjs` (added by this feature) compares first-load size, typefaces included, with `budget.json`;
growth must be at most 150 KB (SC-003). Then, from the repository root, the whole gate (silent on success):

```bash
./gradlew -q verify
```

## 3. Visual suite (FR-015, SC-001, SC-004, SC-005)

```bash
cd frontend && npm run visual
```

`visual` (added by this feature) runs `@playwright/test` inside the Playwright container against a Vite `visual` mode
that answers from MSW fixtures, so no platform is needed. It covers:

- 48 screenshots: 8 pages (home, category, product, cart, checkout, confirmation, order history, sign-in) x 360, 768
  and 1280 px x light and dark;
- no horizontal overflow at 360 and 640 px;
- axe with zero violations in both themes;
- a forced-colours smoke check;
- cumulative layout shift below 0.1.

Expected outcome: every test passes, and the 48 screenshots match their committed baselines. To accept an intended
change:

```bash
npm run visual -- --update-snapshots
```

Review the PNG diff of the changed baselines in the pull request before merging; an unreviewed baseline change defeats
the gate.

## 4. Manual walkthrough (dev server)

```bash
cd frontend && npm run dev
```

Point it at the running platform (the dev server proxies `/api` as in feature 005). Check each list in a light window,
then repeat the marked items in dark.

**US1 - recognisable browsing**
- Header shows the "Vibestore" wordmark linking home; home opens with the tagline "Good things, good vibes" and links
  to up to four categories from the real catalogue.
- Cards are image-led (photo, then name, price, availability) at one aspect ratio; the whole card is one focusable
  link. Break an image URL: a branded placeholder of the same size appears, with no layout shift.
- Home, category and search share headings, spacing and grid. Tab title and icon read Vibestore.

**US2 - product, cart, checkout**
- Product page: name, then price and the accent-coloured "add to cart" as the most prominent elements.
- Throttle the network: skeletons shaped like the content appear and nothing jumps on arrival.
- Empty cart, empty search and a forced API failure show the branded empty or error state with one next action.
- Address, payment method, countdown and confirmation share one component family; a declined payment and a
  validation error are distinct and still announced. Remove a cart line: the dialog matches and keeps its focus
  behaviour.

**US3 - accessibility** (light and dark)
- Dark system theme gives a warm dark palette; text is readable everywhere.
- Browser zoom 200 % and a 360 px window: no horizontal scroll, nothing clipped.
- Keyboard only: a visible focus ring on every interactive element.
- OS reduced motion on: hover, press, skeleton and transitions are static.
- Forced colours (DevTools rendering emulation): controls, borders, badges and focus remain distinguishable.

**US4 - console**
- The operator console shows the shared palette, typefaces and controls in both themes, with unchanged layout and
  at least as many rows visible at 1280 px as on `main`.

## 5. Font-load failure check (FR-006, SC-004)

In DevTools, Network, block the request pattern `/assets/*.woff2` and hard-reload the home page. Text must be visible
immediately in the fallback face (no blank text), and the page must not visibly jump when you unblock and reload. For
a number, record the Performance panel's layout shift: it must stay below 0.1.

## 6. Regression

Existing scenarios pass with no step edits (SC-002):

```bash
cd frontend && STOREFRONT_URL=http://localhost:8080 npm run acceptance
```

Expected: 100 % pass, and `git diff main -- frontend/acceptance` shows no change to any `.feature` or step file.
No user-visible "Storefront" may remain (SC-007):

```bash
grep -rniw "storefront" frontend/src frontend/index.html
```

Expected: no output apart from internal identifiers (imports, test ids, comments); the package name and other
internal names stay "storefront" by decision. Inspect any hit and confirm it is not rendered text, title or
alt text.

## 7. Side-by-side review (SC-008)

Capture the same three pages before and after, at 1280 px, light theme, on identical seed data:

```bash
git switch main && cd frontend && npm run dev
```

Screenshot home, one product page and checkout (address step) into `before/`. Then switch to this branch, restart the
dev server and repeat into `after/`, using the same viewport and product.

```bash
git switch 007-storefront-visual-identity && cd frontend && npm run dev
```

Show five reviewers each pair, unlabeled and in random order, and record per reviewer two answers: which looks more
distinctive, and whether the new one is at least as trustworthy as the old. Pass when at least 4 of 5 pick the new
design as more distinctive and at least 4 of 5 rate it at least as trustworthy.

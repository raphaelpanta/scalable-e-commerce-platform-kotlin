# Implementation Plan: Storefront Visual Identity

**Branch**: `007-storefront-visual-identity` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-storefront-visual-identity/spec.md`

## Summary

Turn the functional but generic storefront into **Vibestore** ("Good things, good vibes"), a warm editorial
identity, without changing a route, a flow or an accessible name. The work is presentation-only and stays
inside `frontend/`:

- **Tokens**: `src/ui/styles/tokens.css` is rebuilt into primitive and semantic tokens (warm paper, deep ink,
  one terracotta accent, light and dark, forced colours). The existing token names are kept, so components
  migrate gradually. A lint script forbids literal colours and sizes anywhere else, and a unit test proves
  every colour-role pair meets WCAG 2.2 AA in both themes.
- **Type**: Fraunces 600 (display and wordmark) and Source Sans 3 (body, tabular price figures) are
  self-hosted through exact-pinned Fontsource 5.3.0 packages: about 47 KB of latin woff2, declared
  woff2-only so Vite does not emit the extra `.woff` files. Vite emits them as hashed same-origin assets, so the
  CSP (`font-src 'self'`) is untouched. Metric-matched fallback faces keep text visible and stop layout shift.
- **Surfaces**:
  - A branded header and footer, plus an editorial home introduction built on the existing `useCategories`
    hook.
  - Image-led product cards with a fixed ratio and a branded placeholder.
  - One component family for buttons, fields, radio cards, badges, dialog, pager and notices.
  - Skeleton variants of `Loading`, and branded empty and error states.
  - The console only swaps to the new tokens and type.
- **Proof**: a new deterministic visual suite (`frontend/visual/`, `@playwright/test` in the official
  Playwright Linux image, MSW fixtures from `tests/msw/`) holds 48 baselines (8 pages × 3 widths × 2 themes).
  It also checks overflow, axe in both themes, forced colours and CLS. A bundle-budget script caps first-load
  growth at 150 KB. The Cucumber acceptance suite runs unchanged.

## Technical Context

**Language/Version**: TypeScript 5.9 (strict) and CSS Modules; Node 24 (`frontend/.nvmrc`)

**Primary Dependencies**:
- Existing: React 19.3, React Router 8.4, TanStack Query 5, Vite 8.3.
- Added runtime packages: `@fontsource/fraunces` and `@fontsource-variable/source-sans-3`, exact pins
  ([research §1](research.md)).
- Added dev package: `@playwright/test` 1.63.0, the same version as `playwright`.

**Storage**: N/A (no data changes)

**Testing**:
- Vitest + Testing Library + fast-check: the contrast test, the skeleton and placeholder component tests,
  and updated layout tests.
- `@playwright/test` visual suite inside `mcr.microsoft.com/playwright:v1.63.0-noble`.
- Cucumber.js + Playwright acceptance: existing features unchanged, plus a new `visual-identity.feature` for
  the spec's acceptance criteria (constitution §V). Stryker ≥ 80 %, and ESLint/Prettier.

**Target Platform**: evergreen browsers, served by the nginx storefront container behind the gateway

**Project Type**: web frontend (single-page app) inside the Gradle monorepo, already wired into `verify` via
`frontendCheck`

**Performance Goals**:
- First-load transfer growth ≤ 150 KB, fonts included (SC-003).
- CLS < 0.1 on home and product (SC-004).
- Text visible on first paint (no FOIT).

**Constraints**:
- CSP without `unsafe-inline` (`docs/gateway.md:228`), so no inline styles and no third-party hosts.
- 360 px minimum width, 44 px targets and the always-visible focus ring are kept.
- Routes, flows, accessible names and acceptance steps are unchanged (FR-014).
- Internal "storefront" names stay (spec clarification).

**Scale/Scope**: 20 pages, 30 shared components and 6 console views. The 60 literal CSS values outside
`tokens.css` (1 colour, 59 lengths) move to tokens.

No NEEDS CLARIFICATION remains. The open technical choices are settled in [research.md](research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Gate | Pre-research | Post-design |
|---|-----------|------|--------------|-------------|
| I | Kotlin-idiomatic monorepo | No Gradle change beyond what `frontendCheck` already runs. npm pins are exact in `package-lock.json` (the frontend's catalogue), and `checkVersionLiterals` skips `node_modules/` | PASS | PASS |
| II | Clean/Hexagonal + DDD | Only `ui/` changes, plus one `ui/brand` module. The featured-category selection is a pure function in `app/catalog`, and the ESLint import boundaries are unchanged | PASS | PASS – [data-model.md](data-model.md) |
| III | Security by design | The threat model is in the spec. No new origin, CSP unchanged, fonts bundled same-origin, no `dangerouslySetInnerHTML` (SVG mark as JSX), osv-scanner covers the new packages | PASS | PASS – OWASP table below |
| IV | Functional & non-blocking | Selection and contrast maths are pure functions with property tests. No new effects | PASS | PASS |
| V | Layered test contract | Property tests (contrast maths), component tests (skeleton, placeholder, brand), Pacts untouched (no API change), existing acceptance unchanged plus a new `visual-identity.feature` for this spec's criteria, and the visual layer. Stryker stays ≥ 80 % on the new modules | PASS | PASS – [contracts/visual-baselines.md](contracts/visual-baselines.md) |
| VI | Microservice boundaries | No backend or contract change. The home section reuses the catalogue's existing categories endpoint | PASS | PASS |
| VII | TypeScript + React frontend | Strict TS, functional components, typed client untouched, ESLint/Prettier, CSP-compatible bundle | PASS | PASS |
| VIII | Token-efficient harness | Quiet npm scripts (`scripts/quiet.mjs`). The visual suite reports only failures and diff paths. Implementation is split across parallel subagents sized per task (Execution Strategy below) | PASS | PASS |

No gate failures.

### OWASP mapping (Principle III)

| Area | Assets | Abuse cases | Controls |
|------|--------|-------------|----------|
| Page shell | CSP, bundle integrity | Third-party font or CDN compromise and tracking; CSP loosened for convenience | Fonts bundled from pinned packages and served from `/assets/` (A05, A06, A08); CSP unchanged and asserted by the existing gateway tests |
| Catalogue content in the new sections | Page integrity | Injection through category or product names | React escaping, no raw HTML (A03). The SVG mark is static JSX, not fetched markup |
| Images | Layout, shopper trust | Hostile or oversized image URLs | Fixed-ratio frame plus the existing `onError` placeholder; `img-src` policy unchanged |
| Prominence of critical notices | Shopper trust (price, payment state) | Restyling that hides the price-change, decline or throttle notices | The notice and error roles are fixed in [ui-surfaces.md](contracts/ui-surfaces.md); visual baselines cover checkout and cart |
| Dependencies | Supply chain | Malicious font package update | Exact pins and lockfile; osv-scanner in the storefront pipeline; versions at least 2 weeks old |

## Project Structure

### Documentation (this feature)

```text
specs/007-storefront-visual-identity/
├── plan.md              # This file
├── research.md          # Phase 0: typefaces, fallbacks, enforcement, visual harness, budget
├── data-model.md        # Phase 1: tokens, contrast pairs, brand, skeletons, baselines
├── quickstart.md        # Phase 1: validation and run guide
├── contracts/
│   ├── design-tokens.md     # Authoritative token values and the contrast matrix
│   ├── ui-surfaces.md       # Per-surface anatomy and the accessible names that must not change
│   └── visual-baselines.md  # Screenshot matrix, fixtures, thresholds, update flow
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── index.html                       # <title> Vibestore, SVG favicon link
├── public/                          # NEW folder: favicon.svg (brand mark); the MSW worker script
│                                    # is served from here in `visual` mode only
├── package.json                     # + font packages, @playwright/test, scripts: visual, lint hook
├── budget.json                      # NEW: first-load size baseline (from main)
├── scripts/
│   ├── check-design-tokens.mjs      # NEW: no literal colours/sizes outside tokens.css (in `lint`)
│   └── check-bundle-budget.mjs      # NEW: gzip first-load growth ≤ 150 KB (after `build`)
├── src/
│   ├── main.tsx                     # imports the font CSS once
│   ├── app/catalog/featured.ts      # NEW: pure selection of ≤ 4 featured categories
│   └── ui/
│       ├── styles/{tokens,global,fonts}.css   # rebuilt tokens; NEW fonts.css with fallback faces
│       ├── brand/                   # NEW: Wordmark.tsx, BrandMark.tsx (SVG), brand.ts (name, tagline)
│       ├── components/              # restyled; Loading gains variants; ProductImage frame
│       │                            # and placeholder; Layout header and footer
│       ├── pages/                   # restyled; HomePage gains the editorial introduction
│       └── console/console.module.css  # tokens and type only
├── tests/
│   ├── ui/{layout,states,browse}.test.tsx   # brand name, skeleton, placeholder, featured section
│   └── styles/contrast.test.ts      # NEW: every pair in contracts/design-tokens.md, both themes
└── visual/                          # NEW: @playwright/test suite
    ├── playwright.config.ts         # projects: 360/768/1280 × light/dark; snapshot threshold
    ├── harness/                     # Vite `visual` mode entry: MSW browser worker + fixtures
    │                                # (handlers' `http://localhost` base matched to the dev origin)
    ├── pages.spec.ts                # 48 toHaveScreenshot baselines
    ├── quality.spec.ts              # overflow 360/640, axe both themes, forced colours, CLS
    └── __screenshots__/             # committed baselines (Linux image only)
.github/workflows/storefront.yml     # + "Visual regression" step in the Playwright container
docs/                                # frontend design notes: tokens and how to update baselines
```

**Structure Decision**: everything stays inside the existing `frontend/` package, keeping its layering
(`domain`/`app`/`api`/`ui`). The only new top-level folder is `frontend/visual/`, because the visual suite
needs its own Playwright runner and fixtures and must not run under Vitest or Cucumber.

## Requirement Traceability

| Spec item | Design element |
|-----------|----------------|
| FR-001, FR-002 | `tokens.css` per [design-tokens.md](contracts/design-tokens.md); `check-design-tokens.mjs` in `lint` |
| FR-003 | `ui/brand/*`, `Layout` header and footer, `index.html` title and favicon; `layout.test.tsx` updated to "Vibestore"; SC-007 grep in quickstart |
| FR-004 | `fonts.css`, type-scale tokens; `tnum` on `Money` |
| FR-005 | Fontsource packages bundled same-origin (research §1); CSP untouched |
| FR-006 | `font-display: swap` and metric-matched fallback faces (research §2); CLS assertion |
| FR-007 | `app/catalog/featured.ts` and the `HomePage` editorial section (research §10) |
| FR-008 | `ProductImage` ratio frame and branded placeholder |
| FR-009 | Button, field, badge, dialog, pager and notice modules per [ui-surfaces.md](contracts/ui-surfaces.md) |
| FR-010 | `Loading` variants, `Empty`/`ErrorState` restyle and copy |
| FR-011 | Interaction tokens; reduced motion sets `--motion-duration: 0ms` |
| FR-012, SC-006 | `tests/styles/contrast.test.ts` over the contract's pair matrix |
| FR-013, SC-005 | `visual/quality.spec.ts` (overflow, forced colours); existing 44 px and focus tokens |
| FR-014, SC-002 | No route or name changes; acceptance suite run unchanged |
| FR-015 | `visual/pages.spec.ts` plus the CI step (outside `verify`, see Complexity Tracking) |
| FR-016 | `console.module.css` on semantic tokens; console baselines are not added (layout unchanged) and console axe runs in acceptance |
| SC-001 | Acceptance axe (light) plus visual axe (light and dark) |
| SC-003 | `check-bundle-budget.mjs` against `budget.json` |
| SC-004 | CLS and a delayed-font first-paint check in `quality.spec.ts` |
| SC-008 | Manual review protocol in [quickstart.md](quickstart.md) |

## Execution Strategy (user instruction: parallel subagents, model per complexity)

Implementation runs in parallel worktree agents (`isolation: worktree`, at most 3 builds at once), merged in
dependency order. Every prompt lists exact files and acceptance criteria, so the smaller models don't need to
explore.

| Wave | Work package | Model / effort | Depends on |
|------|--------------|----------------|------------|
| 1 | Tokens, `fonts.css`, font packages, `check-design-tokens.mjs`, contrast test | Sonnet / medium | none |
| 1 | Visual harness: `@playwright/test`, Vite `visual` mode with the MSW worker, container script, quality spec (no baselines yet) | Opus / high (determinism and container quirks, see the podman memory) | none |
| 1 | `budget.json` captured from `main` and `check-bundle-budget.mjs` | Haiku / low | none |
| 2 | Brand module, `Layout`, `index.html`, favicon, home editorial section with `featured.ts` | Sonnet / medium | Wave 1 tokens |
| 2 | Browse surfaces: `ProductCard`, `ProductImage`, `ProductGrid`, category, search and product pages | Sonnet / medium | Wave 1 tokens |
| 2 | Transaction surfaces: cart, checkout, payment, order pages, dialog, forms, badges, notices, skeleton and empty/error states | Sonnet / medium | Wave 1 tokens |
| 3 | Console token migration, docs, CI visual step | Sonnet / low | Wave 2 |
| 3 | Baseline generation, full gate (`./gradlew -q verify`, visual, acceptance), fixing failures | Opus / high, run by the orchestrator in the foreground | all |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| A second Playwright runner (`@playwright/test`) next to Cucumber + Playwright | FR-015 needs deterministic screenshot comparison with diff output, which `toHaveScreenshot` provides | Screenshots in Cucumber need the live platform and its changing data; hand-rolled pixel diffing duplicates the runner |
| Visual suite and size budget run in CI and on demand, not inside `./gradlew -q verify` | The visual suite needs a container engine and about a minute per run; `verify` must stay fast and must not depend on podman/docker being up (podman socket drops under load). The budget needs a production build, which `verify` does not run | Wiring them into `verify` would make the local gate fail on machines without a container engine; an opt-in Gradle task that silently skips would hide failures. Both run as named CI steps on every storefront change (T046) and locally with `npm run visual` / `npm run budget` |
| Baselines rendered only inside a pinned Linux container | Font rasterisation differs between macOS and Linux, so baselines from mixed hosts would always fail | Per-OS baselines double the maintenance and still fail on CI runner drift |

## Phase Status

- [x] Phase 0 research complete → [research.md](research.md)
- [x] Phase 1 design complete → [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)
- [x] Constitution Check re-evaluated after design (table above)
- [ ] Phase 2 tasks → `/speckit-tasks`

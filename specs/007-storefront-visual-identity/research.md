# Research: Storefront Visual Identity (007)

Phase 0 of planning. Spec: `specs/007-storefront-visual-identity/spec.md`. Facts were checked on 2026-10-08.

## Corrections

1. Playwright: `1.63.0` exists for both `playwright` and `@playwright/test`, but `1.64.0` was published today
   (2026-10-08). Staying on 1.63.0 is correct (it matches the pinned `playwright`, and the Docker image tag
   `v1.63.0-noble` exists). Do not bump as part of this feature.
2. Literal count: there are 60 literal values in `src/**/*.css` outside `tokens.css` (1 colour function, 59 length
   literals), not 55. The figure is a baseline only; the check enforces zero.
3. Font sizes are far below target: 18,096 B (Fraunces 600 latin) + 28,740 B (Source Sans 3 variable latin) = 46,836 B
   (about 45.7 KB), against a 90 KB ceiling.
4. MSW handlers in `frontend/tests/msw/*.ts` use the absolute base `http://localhost` (`API` in `catalog.ts`), not a
   relative `/api`. The `visual` mode must either serve the app on a host where that base matches, or the harness
   must rewrite the base (see §6 risk).
5. `frontend/public/` does not exist yet; it is created by this feature (favicon, and the MSW worker script if
   generated into it, see §6).
6. No other claim in the brief was false.

## §1 Typefaces

**Decision.** Fraunces for display and wordmark, Source Sans 3 for body. Both SIL OFL 1.1. Self-hosted through
exact-pinned Fontsource packages, imported from CSS so Vite emits hashed `woff2` under `/assets/`:

- `@fontsource/fraunces@5.3.0`, only `@fontsource/fraunces/latin-600.css` (static weight 600, latin subset).
- `@fontsource-variable/source-sans-3@5.3.0`, only the `latin-wght` stylesheet (variable weight axis, latin subset).

**Rationale.** Same-origin delivery satisfies `font-src 'self'` and `style-src 'self'` (`docs/gateway.md:228`) and adds
no third-party request (FR-005). A package version is visible to dependency scanning and `package-lock.json`
integrity hashes. Source Sans 3 provides tabular figures (`font-variant-numeric: tabular-nums` -> `tnum`) for aligned
prices. One display weight keeps the transfer small; headings and the wordmark use only 600.

**Verified facts.**
- Both packages at 5.3.0, published 2026-07-19 (about 11 weeks old, so >= 2 weeks before 2026-10-08), licence
  `OFL-1.1`; each package ships a `LICENSE` containing the OFL text. Source: `npm view` on the registry
  (https://registry.npmjs.org/@fontsource/fraunces, https://registry.npmjs.org/@fontsource-variable/source-sans-3).
- `files/fraunces-latin-600-normal.woff2` = 18,096 B; `files/source-sans-3-latin-wght-normal.woff2` = 28,740 B
  (measured from the downloaded tarballs). Total 46,836 B, ceiling 90 KB.
- Fontsource also ships a `.woff` for Fraunces; Vite will emit both only if both `src` entries are referenced. Accept
  the package CSS as is, or use a local `@font-face` that references the `woff2` file only (preferred, to keep
  one format; every supported browser reads woff2).

**Alternatives considered.**
- Google Fonts CDN: rejected, violates the CSP (`font-src 'self'`) and adds a third-party request (FR-005).
- Committing `woff2` files by hand: rejected, weaker provenance, licence tracking and dependency scanning.
- System fonts only: rejected, fails the "distinctive" requirement.

## §2 No invisible text

**Decision.** `font-display: swap` on both families, plus metric-matched fallback faces declared in `tokens.css` or a
`fonts.css` beside it:

| Fallback face           | `src: local(...)`                  | `size-adjust` | `ascent-override` | `descent-override` | `line-gap-override` |
|-------------------------|------------------------------------|---------------|-------------------|--------------------|---------------------|
| `Fraunces Fallback`     | `Georgia`, then `Times New Roman`  | about 104 %   | about 88 %        | about 23 %         | 0 %                 |
| `Source Sans 3 Fallback`| `Arial`, then `Helvetica`          | about 99 %    | about 100 %       | about 28 %         | 0 %                 |

The values are approximate starting points; they are tuned against the visual baselines (§6) until the swap moves
no line. Stacks: `"Fraunces", "Fraunces Fallback", Georgia, serif` and
`"Source Sans 3", "Source Sans 3 Fallback", Arial, sans-serif`. No `<link rel="preload">`: filenames are hashed
and the build does not expose them to `index.html`; CSS discovery is sufficient for two small files. Acceptance:
cumulative layout shift < 0.1 on home and product at 360 and 1280 px (§6, contracts/visual-baselines.md).

**Rationale.** `swap` guarantees text is never invisible; fallback metrics make the swap nearly shift-free.

**Alternatives considered.** `font-display: optional` (rejected: first-visit shoppers may never see the brand type);
preload with a build plugin (rejected: extra tooling for about 47 KB); `size-adjust` computed automatically by a
build tool such as fontaine (rejected: new dependency for two faces).

## §3 Palette

**Decision.** Warm paper, deep ink and one terracotta accent, in light and dark. Structure: a primitive scale
(paper, ink, accent steps, neutrals, feedback hues) and semantic role tokens (`--color-surface`, `--color-text`,
`--color-accent`, `--color-on-accent`, `--color-border`, `--color-focus`, status roles) that components alone
consume. Dark theme through `@media (prefers-color-scheme: dark)` as today. Forced colours
(`@media (forced-colors: active)`) map roles to system colours (`Canvas`, `CanvasText`, `LinkText`, `ButtonText`,
`Highlight`, `GrayText`) and keep borders on controls and badges. Every foreground/background role pair meets
WCAG 2.2 AA: 4.5:1 for text, 3:1 for large text, borders that identify controls, and the focus ring. Exact values
live in `contracts/design-tokens.md`.

**Rationale.** Semantic roles let the operator console inherit the identity by consuming the same tokens, and let
the contrast test (§5) iterate over a short, explicit pair list.

**Alternatives considered.** A class-based theme switch (rejected: no user toggle in scope, system preference is
today's behaviour); a utility CSS framework (rejected: new toolchain, no benefit for a token-driven design).

## §4 Token enforcement

**Decision.** `frontend/scripts/check-design-tokens.mjs`, about 60 lines, run from `npm run lint`. It scans
`src/**/*.module.css` and `src/ui/styles/global.css` and fails on:

- colour literals: hex, `rgb()/rgba()`, `hsl()/hsla()`, `oklch()/lab()/color()` and named colours, except
  `transparent`, `currentColor`, `inherit`;
- raw length literals (`px`, `rem`, `em`, `vh/vw`, `ch`) outside `var(--...)` references.

Allowed: `0`, `100%`, `1fr`, unitless `line-height`, and anything inside `src/ui/styles/tokens.css` (the only file
that defines values). Media-query breakpoints are written as literals in `@media` preludes only if the contract
lists them; otherwise the script ignores `@media` preludes (custom properties cannot be used there). The script
prints file, line and the offending token, exits 1.

**Verified facts.** 60 literals currently exist outside `tokens.css` (59 lengths, 1 colour function); see
Corrections.

**Rationale.** The rule is mechanical and small; it keeps the identity changeable in one place.

**Alternatives considered.** stylelint with `declaration-strict-value` (rejected: two new dev dependencies and
configuration for a 60-line check); a code review rule only (rejected: not enforced).

## §5 Contrast verification

**Decision.** A Vitest unit test (`tests/styles/contrast.test.ts`) parses `src/ui/styles/tokens.css`, extracts the light values and
the `prefers-color-scheme: dark` overrides, resolves the foreground/background pairs listed in the contract and
asserts the WCAG ratio (>= 4.5 or >= 3 as declared per pair). The ratio and relative-luminance functions are pure
and covered by fast-check properties: symmetry (`ratio(a,b) == ratio(b,a)`), range 1 to 21, white against black
= 21, `ratio(a,a) == 1`.

**Rationale.** Runs in the existing unit layer in milliseconds, fails when a token edit breaks AA, and needs no
browser. The axe checks in §6 cover what only a rendered page shows (overlaps, images, gradients).

**Alternatives considered.** Relying on axe alone (rejected: axe skips pseudo-elements and many states); a
third-party contrast library (rejected: the formula is 15 lines).

## §6 Visual baselines (FR-015)

**Decision.** A new `frontend/visual/` suite with `@playwright/test@1.63.0` (exact pin, same as `playwright`).

- Pages render from a Vite dev server in a `visual` mode that starts the MSW browser worker (`msw/browser`) with the
  existing handlers in `frontend/tests/msw/*.ts` and fixed fixtures. Time is frozen with `page.clock`; animations
  and caret are disabled (`animations: 'disabled'`, `caret: 'hide'`).
- Matrix: 8 pages (home, category, product, cart, checkout, confirmation, order history, sign-in) x widths
  360 / 768 / 1280 x light / dark = 48 `toHaveScreenshot` baselines (`colorScheme` per project).
- Baselines are generated and compared only inside `mcr.microsoft.com/playwright:v1.63.0-noble`, run through podman
  or docker, so macOS developers and CI render identically.
- The same suite asserts: no horizontal overflow at 360 px and at 640 px (the 200 % zoom equivalent of 1280 px);
  axe-core zero violations in both themes (the existing acceptance axe run is light only); a forced-colours smoke
  (`page.emulateMedia({ forcedColors: 'active' })`, controls and focus still visible); reduced motion
  (`reducedMotion: 'reduce'`, no running animations); and CLS < 0.1 through a `PerformanceObserver` on
  `layout-shift`.

**Verified facts.**
- `@playwright/test@1.63.0` exists on npm (latest is 1.64.0, published 2026-10-08; do not use it).
- Tag `v1.63.0-noble` (and `-amd64`/`-arm64`) is listed at `https://mcr.microsoft.com/v2/playwright/tags/list`.

**Risks.**
- Handlers use the absolute `http://localhost` base (Corrections item 4). Options: serve the visual build so API
  calls resolve to that base, or have the `visual` entry set the same base the handlers expect through the existing
  configuration. Decide in the plan; it is a harness detail, not a product change.
- The MSW browser worker needs `mockServiceWorker.js` served from the origin (`msw init` into `frontend/public/`,
  or served by a dev-only Vite middleware). It must never reach the production build (`visual` mode only).
- Podman socket drops under load and the tight disk (project memory): run the visual suite as one container, one
  build per tree.

**Alternatives considered.** Screenshots in the Cucumber acceptance suite (rejected: needs the live platform and
non-deterministic data); Storybook with Chromatic (rejected: new toolchain and a third-party service);
pixelmatch by hand (rejected: reinvents `toHaveScreenshot`).

## §7 Transfer budget (SC-003)

**Decision.** `frontend/scripts/check-bundle-budget.mjs`, run after `npm run build`. It reads `dist/index.html` and
the Vite manifest, takes the JS, CSS and font files the home page entry loads (entry chunk, statically imported
chunks, stylesheets, `woff2`), sums their gzip sizes (`zlib.gzipSync`, level 9; fonts are already compressed, so the
raw size is counted) and compares with `frontend/budget.json`. The baseline in `budget.json` is recorded from
`main` before the change. The check fails when growth exceeds 150 KB.

**Rationale.** The fonts (about 46 KB) plus tokens and CSS fit inside 150 KB with room; a recorded baseline makes
regressions visible in review.

**Alternatives considered.** `size-limit` or `bundlewatch` (rejected: new dependency); Lighthouse CI (rejected:
network-dependent and noisy for a byte budget).

## §8 Skeleton loading

**Decision.** Extend `frontend/src/ui/components/Loading.tsx` (keeps `role="status"` and `aria-live="polite"`) with
a `variant` prop: `spinner` (current default), `grid`, `detail`, `lines`. Skeleton blocks are `aria-hidden`; the
accessible label stays in a visually available status text. Dimensions are reserved with `aspect-ratio` and
min-heights from tokens so content arrival moves nothing. The shimmer is a CSS animation, switched off under
`prefers-reduced-motion: reduce` (static blocks) and replaced by bordered blocks under forced colours.

**Rationale.** One component already used by `QueryBoundary`; no new library, no new call sites to learn.

**Alternatives considered.** `react-loading-skeleton` (rejected: new dependency, inline styles conflict with
`style-src 'self'`); per-page skeleton components (rejected: duplication and drift).

## §9 Brand assets

**Decision.** Wordmark "Vibestore" in Fraunces 600 next to a small inline SVG mark rendered by a React component
(`BrandMark`), with `aria-hidden="true"` (the text carries the name) and colours through `currentColor` and CSS
classes, never `style=` attributes (CSP has no `'unsafe-inline'`). SVG favicon `frontend/public/favicon.svg`
(directory created by this feature), linked from `index.html` with `<link rel="icon" type="image/svg+xml">`, served
same-origin (`img-src 'self'`). Document title pattern: `Page — Vibestore` (home: `Vibestore — Good things, good
vibes`), set per route. Internal names containing "storefront" (package, Pact participant, CI workflow, docs, test
steps) stay unchanged, per the spec clarification.

**Rationale.** A same-origin SVG scales, themes through `currentColor` and weighs under 1 KB.

**Alternatives considered.** Raster logo (rejected: blurry, heavier, no theming); font-only wordmark with no mark
(rejected: weaker recognisability in the tab and at small sizes); `.ico` favicon only (rejected: no dark-mode
adaptation).

## §10 Home editorial section

**Decision.** The editorial introduction on the home page shows the tagline "Good things, good vibes" and links to
featured categories taken from the existing `useCategories` hook
(`frontend/src/app/catalog/useCategories.ts`): the first up to four active top-level categories in catalogue order.
The categories block is omitted when none qualify (the tagline remains). No new API call or endpoint: the data is
already cached by the navigation's query key.

**Rationale.** No contract change, no new loading path; a failure of the categories query degrades to the tagline
alone.

**Alternatives considered.** A curated featured list in configuration (rejected: new content to maintain and
possibly stale links); a new `featured` catalogue endpoint (rejected: out of scope, presentation-only feature).

## Execution strategy

The user asked for parallel subagents with the model chosen by task complexity (project memory:
"Subagent cost balance", "Parallel agent worktrees": one build per tree, consumers before providers).

- Sonnet: tokens and fonts CSS, page and component restyling, skeleton variants, brand component and favicon,
  documentation, the token check script, the bundle budget script, visual harness scaffolding (config, fixtures,
  page list, Dockerfile-free `podman run` wrapper).
- Stronger model only where reasoning-heavy: debugging visual harness determinism (fonts, clock, MSW timing,
  podman image behaviour) and wiring the contrast and budget gates if they fail in ways not explained by a token
  edit.
- Work is split by disjoint file sets (tokens and fonts; shared components; pages; visual harness; scripts and
  docs), in separate worktrees, with `./gradlew -q verify` (or the frontend `npm run lint`/`test`) run once per
  tree.

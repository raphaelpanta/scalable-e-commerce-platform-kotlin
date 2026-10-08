# Data Model: Storefront Visual Identity (007)

Presentation-only. No backend, API, domain or persisted entity changes (FR-014). The entities below describe the
design vocabulary the code and tests share. Sources: `frontend/src/ui/styles/tokens.css`, `contracts/*.md`.

## Entities

### DesignToken

| Field | Type | Rules |
|---|---|---|
| name | CSS custom property, kebab-case, `--` prefix | Unique; existing names are kept (see `contracts/design-tokens.md`) |
| layer | `primitive` \| `semantic` | Primitives are raw scale values (spacing, size, ratio); semantics are roles (`--color-*`, `--font-display`) that components consume |
| lightValue | CSS value | Declared on `:root` |
| darkValue | CSS value or none | Declared only in `@media (prefers-color-scheme: dark)`; colour, shadow and skeleton tokens must have one |
| rules | text | Contrast pairs the token participates in; forced-colours mapping |

Validation:
- Components (`*.module.css`, `global.css`) contain no literal colour, size, radius, shadow or duration; every value is `var(--…)` (FR-002). Exception: `0`, `1px` hairlines expressed via `--border-width`, `100%`, `auto`, media-query breakpoints.
- Every colour token has a light and a dark value; every dark value is declared in tokens.css only.
- No token references a third-party origin; fonts are same-origin (`@font-face` `src: url(/fonts/…)`, `font-display: swap`) (FR-005, FR-006).

### ContrastPair

| Field | Type | Rules |
|---|---|---|
| foregroundRole | token name | A colour token |
| backgroundRole | token name | A colour token it is actually rendered on |
| kind | `text` \| `large-text` \| `ui` | `large-text` = 24 px or 18.66 px bold and up |
| minimumRatio | number | text 4.5, large-text 3, ui 3 (WCAG 2.x relative luminance) |
| lightRatio / darkRatio | number | Computed, recorded to 2 decimals in `contracts/design-tokens.md` |

Validation: both ratios >= minimumRatio for every pair, in both themes (SC-006). Muted text is `text`, never `large-text`.

### BrandIdentity

| Field | Value |
|---|---|
| name | `Vibestore` |
| tagline | `Good things, good vibes` (home page only, under the wordmark) |
| wordmark | Text in `--font-display` 600 inside the header link; accessible name `Vibestore` |
| mark | Inline SVG, `aria-hidden`, colours via `currentColor`/tokens; same-origin |
| favicon | `frontend/public/favicon.svg` (plus 180 px PNG touch icon), linked from `index.html` |
| titlePattern | `<Page> — Vibestore` (em dash); home is `Vibestore — Good things, good vibes` |

Validation: zero user-visible `Storefront` strings remain (SC-007); internal names (package, pact participant, CI, docs) keep `storefront`.

### SkeletonVariant

| Variant | Mimics | Used by |
|---|---|---|
| `grid` | N product cards (image block 4/5, two text lines, price line) | Home, category, search listings |
| `detail` | Image block + title, price, buy panel | Product page |
| `lines` | 3 text lines of decreasing width | Cart, orders, account, confirmation, console tables |

Rules: shape only (no text), `aria-hidden` blocks inside one `role="status"` region named as today's Loading text; fill `--color-skeleton`; shimmer only under `prefers-reduced-motion: no-preference`, static otherwise; reserves final height to keep CLS < 0.1.

### FeaturedCategories

| Field | Rules |
|---|---|
| source | Existing category list endpoint already used by `CategoryNav`; no new request |
| count | 0..4: first four in API order |
| at 0 | Section omitted entirely (no heading, no empty box) |
| render | Links to existing category routes; accessible names are the category names |

### VisualBaseline

| Field | Rules |
|---|---|
| page | home, category, product, cart, checkout, confirmation, orders, sign-in |
| width | 360, 768, 1280 |
| theme | light, dark |
| image | `frontend/visual/__screenshots__/<page>-<width>-<theme>.png` (48 files) |

Validation: see `contracts/visual-baselines.md` (diff ratio <= 0.1 %, container-only rendering).

## State: ProductImage loading

| State | Entered when | Renders |
|---|---|---|
| loading | `src` present, image not yet decoded | Framed block at `--image-ratio-*` filled with `--color-skeleton` (no layout shift) |
| loaded | `load` event | `<img alt>` object-fit cover in the same frame |
| failed | `error` event, or `src` missing/empty | Branded placeholder: mark + "No image", `role="img"`, `aria-label="<alt> (no image available)"` (unchanged) |

Transitions: loading -> loaded; loading -> failed; loaded and failed are terminal for a given `src` (a changed `src` resets to loading). Failed never retries.

## Invariants and the test that enforces each

| Invariant | Enforced by |
|---|---|
| Contrast pairs meet minimums in both themes | `frontend/tests/styles/contrast.test.ts` (parses tokens.css, computes WCAG ratios) and axe in visual suite |
| No literal colours/sizes in components | `frontend/scripts/check-design-tokens.mjs` in `npm run lint` (scans `*.css` outside tokens.css) |
| Every colour token has a dark value | `frontend/tests/styles/contrast.test.ts` |
| No third-party origins; CSP unchanged | `check-design-tokens.mjs` (no `http(s)://` in CSS) + existing CSP check in `frontend/tests/` |
| Brand name, title pattern, tagline | `frontend/tests/ui/layout.test.tsx`, `HomePage` test, `document.title` assertions |
| Accessible names unchanged except brand label | Existing RTL suites and acceptance scenarios (SC-002) |
| Featured categories 0..4, omitted at 0 | `HomePage` unit test with 0, 2, 6 categories |
| ProductImage states | `ProductImage` unit test (loaded, error, missing src) |
| Skeleton variants render and are `aria-hidden` | `Loading` unit test per variant |
| No horizontal overflow at 360/640/200 % | `frontend/visual/quality.spec.ts` |
| Zero axe violations, both themes | `frontend/visual/quality.spec.ts` |
| Forced-colours legibility, CLS < 0.1 | `frontend/visual/quality.spec.ts` |
| Baselines match | `frontend/visual/pages.spec.ts` |

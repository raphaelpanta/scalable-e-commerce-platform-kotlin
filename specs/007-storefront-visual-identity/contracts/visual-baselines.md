# Contract: Visual Baselines and Layout Assertions

## Matrix (FR-015)

8 pages x 3 widths x 2 themes = **48 baselines**.

| Page | Route | Fixture (from `frontend/tests/msw/`) |
|---|---|---|
| home | `/` | `catalog.ts`: `categories`, `products` (rake, spade, hoe, soldOutLamp, lantern) |
| category | `/categories/<gardenTools.id>` | `catalog.ts` |
| product | `/products/<rake.id>` | `catalog.ts` |
| cart | `/cart` | `cart.ts` (`cartServer` seeded with two lines) |
| checkout | `/checkout` | `identity.ts` session + `cart.ts` + `order.ts` addresses + `payment.ts` methods |
| confirmation | `/orders/<id>/confirmation` | `order.ts` placed order |
| orders | `/orders` | `order.ts` history (>= 3 orders, mixed statuses) |
| sign-in | `/sign-in` | `identity.ts` |

Widths: 360, 768, 1280 px (height 900, full page capture). Themes via `colorScheme: 'light' | 'dark'`.
Naming: `<page>-<width>-<theme>.png`, e.g. `home-360-dark.png`, stored in `frontend/visual/__screenshots__/`.

Product images in fixtures use a same-origin static asset served by the test harness (never remote), so the baseline is stable and includes one placeholder case (hoe has no image).

## Determinism

| Concern | Rule |
|---|---|
| Rendering environment | Only inside `mcr.microsoft.com/playwright:v1.63.0-noble` (matches `playwright` 1.63.0 in `package.json`); the suite refuses to run elsewhere (checks `PLAYWRIGHT_IN_CONTAINER`) |
| Clock | Frozen with `page.clock.install({ time: '2026-01-15T10:00:00Z' })`; countdowns do not tick |
| Animations | `animations: 'disabled'`, `caret: 'hide'`, `reducedMotion: 'reduce'` |
| Fonts | Self-hosted woff2 awaited via `document.fonts.ready` before capture |
| Data | MSW handlers, fixed ids; no network beyond the app origin |
| Threshold | `maxDiffPixelRatio: 0.001` (0.1 %), `threshold: 0.2` per-pixel colour tolerance |

## Non-screenshot assertions

| Assertion | Pages | Widths/themes |
|---|---|---|
| No horizontal overflow (`documentElement.scrollWidth <= clientWidth`) | all 8 | 360 and 640; also at 200 % zoom (`deviceScaleFactor`/CSS zoom) at 360 |
| axe (`@axe-core/playwright`) zero violations | all 8 + console orders and stock | light and dark |
| Forced-colours smoke (`forcedColors: 'active'`): primary button, links, focus ring and radio selection visible; no text on `Canvas` at 1:1 | home, checkout | 360 and 1280 |
| CLS < 0.1 (`PerformanceObserver` layout-shift sum after load + font swap) | home, product | 360 and 1280 |
| Control sizes >= 44 px for buttons, links in nav, radios | checkout | 360 |
| No third-party request (every request origin equals app origin) | home, product | 1280 |

## Updating baselines

1. Run inside the container: `npm run visual -- --update-snapshots` (wrapper starts `mcr.microsoft.com/playwright:v1.63.0-noble`, mounts `frontend/`).
2. Review every changed PNG in the pull request (side-by-side in the diff); an unexplained change blocks merge.
3. Baselines change only in commits that intentionally change presentation; never regenerated on the host or in CI to make a failure pass.
4. CI runs `npm run visual` (no update flag) in the same image; failures upload actual/diff images as artefacts.

# Contract: Design Tokens

Authoritative table for `frontend/src/ui/styles/tokens.css`. Existing token names are kept so components keep
working; new names are marked **new**. Dark values live in `@media (prefers-color-scheme: dark)`. Ratios were
computed with the WCAG 2.x relative-luminance formula (scratch script, not in the repo).

## Colour

| Token | Light | Dark | Notes |
|---|---|---|---|
| `--color-bg` | `#FBF7F0` | `#17130F` | Warm paper / warm near-black |
| `--color-surface` | `#F3ECDF` | `#201A15` | Bands, cards at rest, table heads |
| `--color-surface-raised` | `#FFFFFF` | `#2A231C` | Inputs, dialogs, popovers |
| `--color-border` | `#D8CCBA` | `#43392F` | Decorative dividers only (not a control boundary) |
| `--color-border-strong` **new** | `#85786A` | `#8C7E6F` | Input, radio, checkbox and button-secondary boundaries (3:1) |
| `--color-text` | `#211C18` | `#F3ECE2` | Ink |
| `--color-text-muted` | `#5C5249` | `#B8AC9E` | Captions, helper text |
| `--color-primary` | `#A8441F` | `#F0936B` | The single terracotta accent |
| `--color-primary-contrast` | `#FFFFFF` | `#17130F` | Text on primary |
| `--color-primary-hover` | `#8A3416` | `#F5A987` | Hover/pressed fill and link hover |
| `--color-accent-subtle` **new** | `#F6E4D7` | `#35221A` | Selected radio card, badge, highlight band |
| `--color-danger` | `#B3261E` | `#F2958C` | Errors (text and boundary) |
| `--color-danger-contrast` | `#FFFFFF` | `#17130F` | Text on danger fill |
| `--color-success` | `#1E6B3A` | `#7FC796` | Paid / in stock text |
| `--color-warning` | `#7A4F00` | `#E8BD62` | Pending / low stock text |
| `--color-focus` | `#1A5FB4` | `#8DB8F5` | Focus ring (blue so it never merges with the accent) |
| `--color-notice-bg` **new** | `#F6E4D7` | `#35221A` | Notice background (price-change, throttle) |
| `--color-notice-text` **new** | `#4A2412` | `#F7D9C8` | Notice text |
| `--color-skeleton` **new** | `#E8DFD0` | `#2F271F` | Skeleton and image-loading fill (decorative) |
| `--shadow-sm` | `0 1px 2px rgb(33 28 24 / 8%)` | `0 1px 2px rgb(0 0 0 / 60%)` | |
| `--shadow-md` **new** | `0 4px 14px rgb(33 28 24 / 10%)` | `0 4px 14px rgb(0 0 0 / 55%)` | Cards on hover, dialogs |

## Type

| Token | Value | Notes |
|---|---|---|
| `--font-display` **new** | `'Fraunces', Georgia, 'Times New Roman', serif` | Self-hosted variable woff2, weight 600, `font-display: swap`, size-adjusted fallback |
| `--font-body` **new** | `'Source Sans 3', system-ui, -apple-system, 'Segoe UI', Roboto, Arial, sans-serif` | Self-hosted variable woff2; `--font-family` is kept as an alias of `--font-body` |
| `--font-size-sm` | `0.875rem` | |
| `--font-size-md` | `1rem` | |
| `--font-size-lg` | `1.25rem` | |
| `--font-size-xl` | `1.5rem` | |
| `--font-size-2xl` | `2rem` | |
| `--font-size-3xl` **new** | `2.5rem` | |
| `--font-size-display` **new** | `clamp(2.25rem, 1.5rem + 3.5vw, 4rem)` | Home hero only |
| `--line-height` | `1.5` | Body |
| `--line-height-tight` **new** | `1.15` | Display and headings |
| `--letter-spacing-display` **new** | `-0.01em` | Fraunces headings |
| `--font-weight-regular` / `--font-weight-bold` | `400` / `600` | Unchanged |
| `--font-numeric` **new** | `tabular-nums lining-nums` | Applied via `font-variant-numeric` to prices |

### Type scale

| Role | Token(s) | Size | Weight | Line height | Family |
|---|---|---|---|---|---|
| Hero (home h1) | `--font-size-display` | 36-64 px | 600 | 1.15 | display |
| Page title (h1) | `--font-size-3xl` (2xl <= 640 px) | 40 / 32 px | 600 | 1.15 | display |
| Section title (h2) | `--font-size-xl` | 24 px | 600 | 1.15 | display |
| Card title (h3) | `--font-size-lg` | 20 px | 600 | 1.15 | display |
| Wordmark | `--font-size-xl` | 24 px | 600 | 1 | display |
| Body | `--font-size-md` | 16 px | 400 | 1.5 | body |
| Price | `--font-size-md`/`lg` + `--font-numeric` | 16-20 px | 600 | 1.5 | body |
| Caption / badge | `--font-size-sm` | 14 px | 400 / 600 | 1.5 | body |
| Button, field label | `--font-size-md` | 16 px | 600 | 1.5 | body |

## Shape, layout, motion (unchanged unless marked)

| Token | Value |
|---|---|
| `--space-1`..`--space-8` | `0.25 0.5 0.75 1 1.5 2 3` rem (unchanged); **new** `--space-10: 4rem`, `--space-12: 6rem` for editorial sections |
| `--radius-sm` / `--radius-md` | `0.25rem` / `0.5rem` (unchanged); **new** `--radius-lg: 0.75rem` (cards, dialogs), `--radius-pill: 999px` (badges) |
| `--border-width` **new** | `1px` (controls `--border-width-strong: 2px` for selected radio cards) |
| `--image-ratio-card` **new** | `4 / 5` |
| `--image-ratio-detail` **new** | `1 / 1` below 768 px, `4 / 5` from 768 px |
| `--focus-ring` / `--focus-ring-offset` | `3px solid var(--color-focus)` / `2px` |
| `--layout-min-width` / `--layout-max-width` / `--layout-gutter` | `360px` / `1440px` / `1rem` (`2rem` from 768 px) |
| `--layout-measure` **new** | `65ch` (reading width for editorial copy) |
| `--control-min-size` | `44px` |
| `--motion-duration` | `150ms` (`0ms` under reduced motion) |
| `--motion-ease` **new** | `cubic-bezier(0.2, 0, 0, 1)` |

## Contrast matrix (WCAG 2.x, final)

Min: text 4.5, large-text 3, ui 3. All pairs pass in both themes.

| Foreground | Background | Kind | Min | Light | Dark |
|---|---|---|---|---|---|
| text | bg | text | 4.5 | 15.81 | 15.76 |
| text | surface | text | 4.5 | 14.37 | 14.68 |
| text | surface-raised | text | 4.5 | 16.88 | 13.21 |
| text-muted | bg | text | 4.5 | 7.13 | 8.30 |
| text-muted | surface | text | 4.5 | 6.48 | 7.73 |
| text-muted | surface-raised | text | 4.5 | 7.61 | 6.96 |
| text-muted | skeleton | text | 4.5 | 5.76 | 6.59 |
| primary-contrast | primary | text | 4.5 | 5.98 | 8.00 |
| primary-contrast | primary-hover | text | 4.5 | 8.13 | 9.60 |
| primary (links, quiet buttons) | bg | text | 4.5 | 5.60 | 8.00 |
| primary | surface | text | 4.5 | 5.09 | 7.45 |
| primary | surface-raised | text | 4.5 | 5.98 | 6.71 |
| primary | accent-subtle (badge, selected card) | text | 4.5 | 4.84 | 6.52 |
| primary-hover (link hover) | bg | text | 4.5 | 7.61 | 9.60 |
| text | accent-subtle | text | 4.5 | 13.66 | 12.84 |
| danger | bg | text | 4.5 | 6.12 | 8.32 |
| danger | surface | text | 4.5 | 5.56 | 7.75 |
| danger-contrast | danger | text | 4.5 | 6.54 | 8.32 |
| success | bg | text | 4.5 | 6.11 | 9.25 |
| success | surface | text | 4.5 | 5.55 | 8.62 |
| warning | bg | text | 4.5 | 6.68 | 10.46 |
| warning | surface | text | 4.5 | 6.07 | 9.74 |
| notice-text | notice-bg | text | 4.5 | 10.95 | 11.26 |
| border-strong | bg | ui | 3 | 4.02 | 4.69 |
| border-strong | surface | ui | 3 | 3.65 | 4.37 |
| border-strong | surface-raised | ui | 3 | 4.29 | 3.93 |
| focus | bg | ui | 3 | 5.89 | 9.07 |
| focus | surface | ui | 3 | 5.35 | 8.45 |
| focus | surface-raised | ui | 3 | 6.29 | 7.60 |

Exempt (decorative, no information): `--color-border` on bg (1.48 / 1.64), `--color-skeleton` on bg (1.24 / 1.26). Anything that carries meaning (status, selection) also has text or a 2 px `border-strong`/primary border, never colour alone.

Lowest text pair: primary on accent-subtle, light, 4.84. Lowest overall margin: border-strong on surface, light, 3.65 (minimum 3).

## Forced-colours mapping

Inside `@media (forced-colors: active)`: tokens are not redefined; components use system colours.

| Role | System colour |
|---|---|
| Page, surfaces, cards, inputs | `Canvas` |
| Text, headings, icons | `CanvasText` |
| Links, quiet buttons | `LinkText` |
| Buttons (all variants), pager | `ButtonText` on `ButtonFace`, 1 px `ButtonText` border |
| Selected radio card, current page, focus ring | `Highlight` outline (2 px) |
| Disabled controls, skeleton outline, muted text | `GrayText` |
| Shadows, tints, skeleton fill | removed (`box-shadow: none`; skeleton gets a `GrayText` outline) |

Rules: `forced-color-adjust` is never disabled except on the inline brand mark (`currentColor` keeps it visible); badges keep a border so they remain boxes.

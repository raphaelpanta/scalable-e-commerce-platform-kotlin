# Contract: UI Surfaces

Presentation contract per surface. Styling comes only from tokens (`contracts/design-tokens.md`).

## Non-negotiables (FR-014)

- Routes, page flows, form fields, validation, DOM roles and **every existing accessible name stay**. The only
  text change is the brand label **"Storefront" -> "Vibestore"** (header brand link and any title/footer
  occurrence). It changes `frontend/tests/ui/layout.test.tsx:73` (`getByRole('link', { name: 'Storefront' })`
  becomes `'Vibestore'`); no other test step may change (SC-002).
- Hit targets >= `--control-min-size` (44 px); focus ring always visible; status never by colour alone; no
  inline styles; no third-party origins.
- Tab order unchanged: skip link, brand, Products, Search, Cart, then the session controls.

## Shell

| Surface | Contract | Names/roles that stay |
|---|---|---|
| Header | Paper band with `--color-border` bottom rule; wordmark (Fraunces 600, mark + text) left, nav right; wraps to two rows at 360 px without overflow | `banner`; brand link name `Vibestore` (mark is `aria-hidden`); `nav` "Primary"; links Products, Search, Cart (badge `aria-label` "N items in cart"), Orders, Account, Console, button Sign out / link Sign in |
| Skip link | Visible on focus, first stop | link "Skip to main content" |
| Footer | `--color-surface` band: wordmark, tagline, copyright line; links only to existing routes | `contentinfo` |
| Title | `<Page> — Vibestore` | `document.title` |

## Home (editorial)

| Block | Contract |
|---|---|
| Hero | The page keeps its `h1` named "Products" (asserted by tests). Wordmark + tagline "Good things, good vibes" appear as a lead paragraph in `--font-size-display`, `<p>` not a heading, so heading outline is unchanged |
| Featured categories | 0-4 links from the existing category list, as large editorial tiles (surface, `--radius-lg`); omitted at 0; names are the category names |
| Listing | Product grid beneath: 2 columns at 360 px, 3 at 768, 4 at 1280; `--space-5` gutters |

## Product card

Whole card is one link (single tab stop, name = product name as today).

| Part | Contract |
|---|---|
| Image | `--image-ratio-card` (4/5), object-fit cover, `--radius-md`, `--color-skeleton` while loading, placeholder on failure (see data-model ProductImage states) |
| Name | Card title (Fraunces 600, `--font-size-lg`), max 2 lines |
| Price | `Money`, tabular figures, 600 |
| Availability | Text always ("In stock" / "Out of stock", the existing strings), with success/warning/muted colour as reinforcement only |
| Hover/press | Image scale <= 1.02 and `--shadow-md` over `--motion-duration`/`--motion-ease`; none under reduced motion |

## Product page

Order: breadcrumb/back link, image (`--image-ratio-detail`) beside (>= 768 px) or above title (h1, display), price (large, tabular), availability text, quantity + add-to-cart (primary), description (measure `--layout-measure`). Names unchanged: "Add to cart", quantity input label, availability text.

## Cart and checkout component family

| Component | Contract |
|---|---|
| Buttons | `primary` (primary fill, primary-contrast text, hover `--color-primary-hover`, pressed translateY(1px)), `secondary` (transparent, `--border-width` `--color-border-strong` border, text colour), `quiet` (no border, `--color-primary` text, underline on hover). Same `buttons.module.css` classes; min 44 px; disabled = `--color-text-muted` on `--color-surface`, `not-allowed` |
| Fields | `--color-surface-raised`, 1 px `--color-border-strong`, `--radius-md`; label above (600); helper in muted; error: `--color-danger` text + border 2 px + icon-free text message linked by `aria-describedby` (as today) |
| Radio cards (address, payment) | Unselected: border-strong 1 px. Selected: 2 px `--color-primary` border + `--color-accent-subtle` fill + native radio mark still shown (not colour alone) |
| Badges | `--radius-pill`, `--color-accent-subtle` fill, `--color-primary`/text colour, text "Order: …"/"Payment: …" unchanged; always 1 px border for forced colours |
| Dialog | `--color-surface-raised`, `--radius-lg`, `--shadow-md`, backdrop ink at 50 %; focus trap and names unchanged |
| Pager | Secondary buttons; current page `aria-current` + 2 px primary underline |
| Order summary | `--color-surface` card, totals row in display serif numerals-tabular |
| Notices | `role` unchanged. Price-change notice (`role="alert"`): `--color-notice-bg/text`, 2 px `--color-warning` left rule + h2 "Prices changed" + the accept button as primary; listed old/new prices tabular. Throttle notice (`role="status"`): same notice colours, 1 px `--color-border-strong`, live countdown number tabular; retry disabled until 0 |
| Payment countdown | Notice styling; text remains the carrier of time |

## States

| State | Contract | Brand-voice copy (examples; existing accessible text stays as the asserted string) |
|---|---|---|
| Loading | `role="status"` kept with its label as visually-hidden text; skeleton `grid` / `detail` / `lines`; spinner removed | "Loading…" (hidden) |
| Empty | Centred mark, h2, one message, one primary action | Cart: "Your cart is empty — find something you will love." / action "Browse products"; Orders: "No orders yet. Your first one will land here." |
| Error | Same layout, `--color-danger` title text, one action | "That did not go as planned. Please try again." / "Try again" |
| Not found | Large display "404", one action | "We could not find that page." / "Back to the store" |

Copy may add sentences but must not alter existing headings, button labels or messages asserted by tests; where a test asserts a string, keep it and add copy beside it.

## Operator console

Tokens and typography only (FR-016): body in Source Sans 3, headings in Fraunces, colours/radius from tokens, same button/field classes. Layout, table density, column order and control sizes are not reduced or redesigned. Brand label in the console shell becomes "Vibestore" with its existing "Console" text; roles unchanged.

## Motion

Only hover/press/skeleton shimmer; all via `--motion-duration` and `--motion-ease`; shimmer is behind `prefers-reduced-motion: no-preference`; reduced-motion rule in `global.css` stays.

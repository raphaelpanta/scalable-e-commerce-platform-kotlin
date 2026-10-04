# Performance suite (T115)

Load test of the running Compose stack through the gateway, written for [k6](https://k6.io): evidence for
**SC-002** and **SC-003** of `specs/004-ecommerce-platform-mvp/spec.md`.

**Status: first runs recorded on 2026-10-03 (T151), see "Recorded results" at the end of this page.** On the
development machine the full 1,000/100 profile overloads the single-CPU catalog service; the thresholds are met at
200 browsing and 20 checkout users.

| File | Purpose |
|---|---|
| `browse-and-checkout.js` | the k6 script, two scenarios (`browse`, `checkout`), thresholds and summary |
| `run.sh` | applies the 10k dataset, runs k6 in the `grafana/k6` container, exits non-zero when a threshold fails |
| `seed-10k.sql` | idempotent dataset: 10,000 products with stock and one primary image each |
| `seed-10k-apply.sh` | runs `seed-10k.sql` (or, with `--remove`, `seed-10k-remove.sql`) inside the `catalog-db` container |
| `seed-10k-remove.sql` | deletes the dataset again, with the reservations and stock adjustments attached to it |
| `compose.perf.yml` | Compose override that lifts the gateway's per-source-address rate limits for the run |
| `results/summary.json` | written by every run (git-ignored) |

## Prerequisites

1. **Stack up with `SEED=true`** (the default of `platform/compose/.env.example`): the dataset hangs off the three
   seed categories and the checkout shoppers need identity, cart, order, payment, notification and Mailpit.
2. **Gateway rate limits lifted.** The load generator reaches the gateway from one source address, and the gateway
   limits anonymous callers per source address (`docs/gateway.md`, "Rate limiting"): browse 600 requests/min, standard
   (registration, verification) 120/min, auth (sign-in) 10/min. With the defaults 1,000 browsing VUs get 429 almost
   at once and 100 shoppers need 10 minutes just to sign in. Start (or restart) the stack with the override, which sets
   `GATEWAY_RATELIMIT_REQUESTSPERMINUTE_AUTH`, `..._BROWSE` and `..._STANDARD`:

   ```bash
   cd platform/compose
   docker compose -f docker-compose.yml -f ../perf/compose.perf.yml --profile core --profile observability up -d --build
   ```

   The `checkout` tier (20 orders/min) and the other tiers keyed by account stay at their defaults: the script paces
   each shopper below them (`CHECKOUT_PACE`, 5 s), so the real per-account policy is part of the test. Without the
   override the run still works, but it measures the rate limiter: look at `gateway 429 answers` in the summary.
3. **10,000 products loaded.** `run.sh` applies `seed-10k.sql` itself (skip with `--no-seed`). Before the load starts
   the script's `setup()` aborts the run if the catalogue lists fewer than 10,000 products or the dataset's first
   product is missing.
4. Docker (or Podman) for the `grafana/k6` container, `curl` on the host. No local k6 installation is needed.

## Run

```bash
platform/perf/run.sh                 # seed, then 1,000 browsing + 100 checkout shoppers, ~5.5 minutes
platform/perf/run.sh --no-seed       # dataset already loaded
BROWSE_VUS=100 CHECKOUT_VUS=10 DURATION=1m platform/perf/run.sh   # quick rehearsal
```

Environment (all optional): `GATEWAY_URL` (`http://localhost:8080`), `MAILPIT_URL` (`http://localhost:8025`),
`BROWSE_VUS` (1000), `CHECKOUT_VUS` (100, `0` disables the scenario), `DURATION` (hold time, `3m`), `RAMP_UP` (`2m`),
`THINK_TIME` (seconds between requests of a browsing shopper, 1), `CHECKOUT_PACE` (minimum seconds between two orders
of one shopper, 5), `PERF_PRODUCTS` (10000), `K6_IMAGE`, `CONTAINER_ENGINE`, `K6_HOST`. `run.sh` prints the full list.

Exit status: `0` all thresholds met, `99` a threshold was crossed (k6's own code), `2` the stack is not reachable.

**Container and networking.** The pinned image is `docker.io/grafana/k6:1.7.0` (existence confirmed with
`docker manifest inspect`). On Linux the container uses `--network host`. On macOS and Windows the engine runs in a VM,
so `run.sh` rewrites `localhost` in the URLs to `host.docker.internal` (Docker) or `host.containers.internal`
(Podman); override with `K6_HOST`. If the host alias cannot reach the published ports, set `BIND_ADDRESS=0.0.0.0` in
`platform/compose/.env`, or run k6 natively from this directory:
`cd platform/perf && k6 run -e GATEWAY_URL=http://localhost:8080 browse-and-checkout.js`.
With Podman build the stack with `BUILDAH_FORMAT=docker` as described in `platform/compose/README.md`. The container
raises the file-descriptor limit (`--ulimit nofile=65536`) for the 1,000 connections.

## What the scenarios do

Both ramp from 0 to their VU count over `RAMP_UP` (2 min) and hold for `DURATION` (3 min). Each iteration of `browse`:
list one of the dataset's categories (random page 0-9, 20 items), search a random term (a noun, an adjective, both, or
a noun with a number from the generated names), open a random product; a randomised `THINK_TIME` after each request.

`checkout`, per VU: **once** register through the gateway, read the verification token from Mailpit (`MAILPIT_URL`),
verify, sign in, add an address (the registrations are staggered by the ramp itself: about one new shopper every 1.2
s). Then in a loop: add one random product to the account cart, read the cart for its `revision`, `POST /api/v1/orders`
with a fresh `Idempotency-Key` and the payment token `tok_sim_approve_4242`; a refused checkout empties the cart so the
next order starts clean. A 429 from the gateway is retried after its `Retry-After` (plus jitter, at most
`MAX_429_RETRIES`), keeping the same `Idempotency-Key`. Access tokens (15 min) are renewed through the refresh token.

Classification of every checkout step: 201 and 202 on the order are successes; 409 `insufficient-stock` on the order
(or 422 `insufficient-stock` when adding to the cart) is an **allowed stock refusal** (counter `stock_refusals`); any
other status is a failure (counter `checkout_failures`, tagged with step and status; the first three per VU are
logged).

## Success criteria and thresholds

| Criterion | Threshold (fails the run) |
|---|---|
| SC-002 browse and search p95 < 1 s with 10,000 products | `http_req_duration{scenario:browse}` `p(95)<1000`; also per request kind (`kind:browse_list`, `browse_search`, `browse_detail`) |
| SC-003 1,000 concurrent browsers, no errors | `http_req_failed{scenario:browse}` `rate<0.01` |
| SC-003 100 concurrent checkouts, no errors other than stock refusals | `checkout_errors` `rate<0.01` (failures excluding stock refusals over every classified checkout step, onboarding included) |

SC-003 allows no errors at all; 1 % is the tolerance of the measurement (a single dropped connection must not fail an
otherwise clean run), so read the absolute counts as well: a clean run reports `failures 0`.

## Reading the summary

The console shows a compact report: the SC-002 and SC-003 verdicts, per-kind latency (p50/p95/p99), the order and
stock-refusal counts, the number of gateway 429 answers and the pass/fail list of thresholds.
`results/summary.json` holds the same verdicts under `verdict`, the run configuration under `config` and every metric
of the run under `metrics` (`values` and `thresholds` per metric). What to look at:

- `throttled_responses` **must be 0**. Otherwise the rate-limit override is missing and the run measured the limiter.
- `stock_refusals` is expected to be small: stock per product is 5-500 and shoppers pick random products out of 10,000.
  A high number means the dataset was consumed by earlier runs (`seed-10k.sql` does not reset stock: run
  `seed-10k-apply.sh --remove` and apply again).
- `checkout_failures` by `step` and `status` names the failing call (for example `sign-in`/`429`, `order`/`500`).
- Correlate with Grafana (**Service RED**, per-service p95 and error rate) and Loki by `X-Correlation-Id` (the order
  requests carry a generated one).

## Clean-up

`platform/perf/seed-10k-apply.sh --remove` deletes the products, stock, images, reservations and adjustments of the
dataset from the catalogue database. Accounts, carts, orders, payments and notifications created by the `checkout`
scenario live in other databases and in Mailpit and stay behind; `docker compose down -v` gives a pristine stack.
Mailpit keeps only a limited number of messages (500 by default), which is enough because each shopper reads its
verification mail within seconds.

## Known limits

- **Single machine.** The stack (six services, six databases, Kafka, the observability profile) and the load generator
  compete for the same CPU and memory; a result is a statement about this machine, not about the architecture. Give
  the container engine enough memory (about 8 GB for the stack) and note the hardware next to the numbers. For a fairer
  reading run k6 from another machine against `BIND_ADDRESS=0.0.0.0`.
- **In-memory rate limiter per gateway instance** (MVP deviation in `docs/gateway.md`). The override lifts it for the
  run; with several gateway instances every instance has its own buckets, so a limit measured on one instance says
  little about a scaled deployment.
- Catalogue search is a case-insensitive `LIKE` over name and description (no full-text index), so its cost grows with
  the catalogue: that is what SC-002 is meant to catch at 10,000 products.
- 1,000 VUs with a 1 s think time generate roughly 1,000 requests per second, more than the 1,000 *shoppers* a human
  think time would produce; it is a deliberately conservative reading of SC-003. Raise `THINK_TIME` for a softer one.
- The quickstart describes registration as `201`; the implemented contract (`identity.yaml`) answers `202`. The script
  accepts both.
- Payments use the simulator, so payment latency is not representative of a real provider.

## Recorded results

Machine for every row: macOS host, Podman 6.0.2 machine (libkrun, 8 CPUs, 11.6 GiB) running the whole `core` +
`observability` stack (each service `cpus: 1.0`, `mem_limit: 768m`, heap 40 %; Tempo 2g) **and** the k6 container;
gateway on port 18080 with `compose.perf.yml`; 10,000 products (`seed-10k.sql`); default `RAMP_UP` 2m, `DURATION` 3m,
`THINK_TIME` 1 s, `CHECKOUT_PACE` 5 s. Throttled answers (429) were 0 and no container restarted in these runs.

| Date | VUs (browse/checkout) | Browse p95 all / list / search / detail | Browse failed | Orders placed | Checkout error rate | Stock refusals | Requests/s | Verdict |
|---|---|---|---|---|---|---|---|---|
| 2026-10-03 | 1,000 / 100 | 7,687 / 7,659 / 7,739 / 7,647 ms | 42.4 % (gateway 504 after 5 s) | 187 | 53.4 % (503 behind the catalogue) | 0 | 163 | **FAIL** (every threshold) |
| 2026-10-03 | 400 / 40 | 2,854 / 2,449 / 3,155 / 2,734 ms | 0 % | 1,074 | 0 % | 0 | 153 | **FAIL** (SC-002 latency only) |
| 2026-10-03 | 200 / 20 | 457 / 377 / 550 / 437 ms | 0 % | 956 | 0 % | 0 | 152 | **PASS** (every threshold) |

Reading: the stack serves about 150 to 160 requests per second through the gateway on this machine. 200 users with a
1 s think time ask for about that much, so they are served within SC-002; from 400 users on the catalog service sits at
its 1-CPU quota (100 %) and its database at about 2 CPUs, latency grows (400 users: no errors, p95 2.9 s) until the
gateway's 5 s upstream timeout turns waits into 504 and the cart and order calls into the catalogue into 503 (1,000
users). SC-002 and SC-003 are therefore met at 200/20 and not at the specified 1,000/100 on a single laptop that also
runs the load generator; the full profile needs a scaled catalogue (`--scale catalog=N`, more CPUs per service) or a
separate, larger machine.

Fixes made while recording these runs (the first 1,000-user attempts had catalog, cart and Tempo OOM-killed): the JVM
heap is 40 % of the 768 MiB limit, Netty's direct buffers are capped at 128 MiB and glibc keeps two malloc arenas
(platform/docker/README.md, "Runtime settings"); Tempo has its own limit, `TEMPO_MEM_LIMIT` (2g: at 512m and 1g it was
OOM-killed in a loop, every request being traced, and its restarts starved the services of CPU); `seed-10k.sql` prices
are multiples of 10 (the payment simulator declines totals ending in 13 or 14, which showed up as `payment-declined`
checkout failures).

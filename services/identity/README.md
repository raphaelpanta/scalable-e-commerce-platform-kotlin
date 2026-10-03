# identity

Service for the bounded context **identity** (user story 3, FR-004..FR-007): registration with email verification,
sign-in with throttling, EdDSA (Ed25519) access tokens of 15 minutes and rotating opaque refresh tokens of 30 days,
sign-out, password reset, profile, delivery addresses, notification preferences with SMS phone verification and
account deletion by anonymisation (`contracts/openapi/identity.yaml`); the JWKS document and the internal address
and contact lookups (`contracts/internal/identity-internal.yaml`); the account events `AccountRegistered`,
`AccountVerified`, `PasswordResetRequested` and `AccountDeleted` on `identity.account.v1` through the outbox.

Package root: `com.ecommerce.identity`.

| Layer | Where | Run alone |
|---|---|---|
| unit | `domain/src/test`, `application/src/test`, adapters and architecture rules in `infrastructure` | `./gradlew -q :services:identity:domain:test` |
| integration | `infrastructure/src/integrationTest` (Testcontainers PostgreSQL, Kafka, Mailpit) | `./gradlew -q :services:identity:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest` (Pact consumer in `contractTest`, provider tagged `provider` in `contractVerify`) | `./gradlew -q :services:identity:infrastructure:contractTest :services:identity:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` (Cucumber) | `./gradlew -q :services:identity:infrastructure:acceptanceTest` |

Running: `IDENTITY_DB_HOST` (default `localhost`), `IDENTITY_DB_USER`, `IDENTITY_DB_PASSWORD`,
`KAFKA_BOOTSTRAP_SERVERS`, `INTERNAL_API_TOKEN`, `JWT_ISSUER`, `JWT_AUDIENCE`, `SMTP_HOST`, `SMTP_PORT`,
`IDENTITY_SIGNING_KEY` (required, see below; optionally `IDENTITY_SIGNING_KEY_ID`), optionally
`IDENTITY_SOURCE_MAX_FAILURES` and `SEED`, then `./gradlew -q :services:identity:infrastructure:bootRun` (or
`SPRING_PROFILES_ACTIVE=dev` instead of a key, for a throw-away one). Health on `http://localhost:8081/actuator/health`.

## Signing keys

Access tokens are signed with an Ed25519 key whose `kid` is the RFC 7638 thumbprint of its public key (or
`IDENTITY_SIGNING_KEY_ID`). Every instance must sign with the same persisted key (FR-024: tokens of one instance
validate against the JWKS of any other, and survive a restart), so `IDENTITY_SIGNING_KEY` is **required**: PKCS#8 as
PEM or bare Base64 of the DER, generated with

```bash
openssl genpkey -algorithm ed25519 -outform DER | base64
```

Without it the service refuses to start, unless the Spring profile `dev` or `test` is active: then a throw-away key
pair is generated at start-up (the integration, contract and acceptance layers include the profile `test` in their
`config/application.yml`). Compose reads the key from `platform/compose/.env` (`platform/compose/README.md`).
`GET /.well-known/jwks.json` publishes the active key and, after a rotation, the previous one; it answers 503
`unavailable` while no key is available. Identity validates its own tokens against the same keys in memory, without an
HTTP call to its JWKS.

## Sign-in throttling

FR-006: 5 consecutive failed sign-ins lock for 15 minutes, counted per account, per email without a live account (so
that a 429 tells nothing about the existence of an account) and per source address (the first `X-Forwarded-For`
entry). Locks are checked before the Argon2id verification, and failures are counted under a row lock
(`SELECT ... FOR UPDATE`), so parallel attempts cannot slip past the limit. The per-source limit
(`identity.sign-in.source-max-failures`, `IDENTITY_SOURCE_MAX_FAILURES`) defaults to the same 5; an environment where
many people share one address (the Compose stack, whose acceptance runner is a single address) raises it. Only SHA-256
hashes of addresses and emails are stored (`sign_in_source`).

## Data retention

`IdentityPurgeJob` (data-model section 5) deletes, at start-up and every `identity.retention.purge-interval` (1 h):
verification and reset tokens 7 days after expiry, sessions (with their refresh-token hashes) 30 days after expiry or
revocation, sign-in throttles idle and unlocked for a day, and phone verifications a day after expiry
(`identity.retention.*`). Accounts are never deleted: deletion anonymises them at once (FR-007).

## Seed data

`SEED=true` activates the Spring profile `seed` (`SeedProfile`, applied in `main`), which adds `classpath:db/seed` to
the Flyway locations: `R__seed.sql` creates the operator `operator@ecommerce.example` / `Operator-Passw0rd!2026`
(roles shopper and operator, verified). The script holds an Argon2id hash of the password; `SeedScriptSpec` checks
that it matches, and its header says how to regenerate it.

## Blocking exceptions

Two calls of this service cannot be made non-blocking, so they never run on a Netty or Reactor event loop (docs/build.md
"Blocking exception"): Argon2id hashing (`Argon2idPasswordHasher`, deliberately slow CPU work) runs on
`Dispatchers.Default`, and the SMTP send of the simulated SMS channel (`SimulatedSmsSender`, JavaMail) on
`Dispatchers.IO`.

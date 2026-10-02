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
`KAFKA_BOOTSTRAP_SERVERS`, `INTERNAL_API_TOKEN`, `JWT_ISSUER`, `JWT_AUDIENCE`, `SMTP_HOST`, `SMTP_PORT`, optionally
`IDENTITY_SIGNING_KEY` (and `IDENTITY_SIGNING_KEY_ID`), `IDENTITY_SOURCE_MAX_FAILURES` and `SEED`, then
`./gradlew -q :services:identity:infrastructure:bootRun`. Health on `http://localhost:8081/actuator/health`.

## Signing keys

Access tokens are signed with an Ed25519 key whose `kid` is the RFC 7638 thumbprint of its public key (or
`IDENTITY_SIGNING_KEY_ID`). Without `IDENTITY_SIGNING_KEY` a key pair is generated at start-up: fine for a single
instance, but tokens then stop validating after a restart, and several instances must share one key
(`openssl genpkey -algorithm ed25519`, PEM or bare Base64 of the PKCS#8 DER). `GET /.well-known/jwks.json` publishes
the active key and, after a rotation, the previous one; it answers 503 `unavailable` while no key is available.
Identity validates its own tokens against the same keys in memory, without an HTTP call to its JWKS.

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

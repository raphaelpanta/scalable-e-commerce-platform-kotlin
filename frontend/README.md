# frontend (reserved)

This directory is reserved for the web storefront (TypeScript + React, constitution Principle VII).
No application lives here yet: the storefront is created by a separate feature.

When it is added, `frontend/package.json` must expose two npm scripts:

- `lint`: static checks (ESLint, type check)
- `test`: the unit test suite

As soon as `frontend/package.json` exists, the root build registers `frontendLint`, `frontendTest`
and `frontendCheck`, and `./gradlew -q verify` runs, after the JVM checks are configured:

```bash
npm --silent --prefix frontend run lint
npm --silent --prefix frontend run test
```

Both must exit 0 and stay silent on success (Principle VIII). Until then `verify` skips the frontend
(FR-015). Use `-PnpmExecutable=/absolute/path/to/npm` when `npm` is not on the Gradle daemon's PATH.

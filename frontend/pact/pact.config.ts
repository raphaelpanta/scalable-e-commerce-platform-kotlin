import path from 'node:path';

import { PactV4, SpecificationVersion } from '@pact-foundation/pact';

// Shared options of every storefront consumer pact. Pact files land in the repository root
// `build/pacts` (the folder every provider's `contractVerify` reads), named
// `storefront-<provider>.json`; run `npm run pact` before `./gradlew -q contractVerify`.
export const PACT_DIR = path.resolve(import.meta.dirname, '..', '..', 'build', 'pacts');
export const CONSUMER = 'storefront';

export type Provider = 'identity' | 'catalog' | 'cart' | 'order' | 'payment' | 'gateway';

export function pactFor(provider: Provider): PactV4 {
  return new PactV4({
    consumer: CONSUMER,
    provider,
    dir: PACT_DIR,
    spec: SpecificationVersion.SPECIFICATION_VERSION_V4,
    logLevel: 'warn',
    host: '127.0.0.1',
  });
}

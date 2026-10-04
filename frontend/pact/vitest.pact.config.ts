import { fileURLToPath, URL } from 'node:url';

import { defineConfig } from 'vitest/config';

const alias = {
  '@domain': fileURLToPath(new URL('../src/domain', import.meta.url)),
  '@app': fileURLToPath(new URL('../src/app', import.meta.url)),
  '@api': fileURLToPath(new URL('../src/api', import.meta.url)),
  '@telemetry': fileURLToPath(new URL('../src/telemetry', import.meta.url)),
  '@ui': fileURLToPath(new URL('../src/ui', import.meta.url)),
};

// Pact consumer tests run in Node against the Pact mock server, one file at a time (each file
// owns a mock-server port), and exercise the real src/api client code.
export default defineConfig({
  resolve: { alias },
  test: {
    environment: 'node',
    include: ['pact/**/*.pact.test.ts'],
    fileParallelism: false,
    passWithNoTests: true,
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});

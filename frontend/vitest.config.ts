import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const alias = {
  '@domain': fileURLToPath(new URL('./src/domain', import.meta.url)),
  '@app': fileURLToPath(new URL('./src/app', import.meta.url)),
  '@api': fileURLToPath(new URL('./src/api', import.meta.url)),
  '@telemetry': fileURLToPath(new URL('./src/telemetry', import.meta.url)),
  '@ui': fileURLToPath(new URL('./src/ui', import.meta.url)),
};

export default defineConfig({
  plugins: [react()],
  resolve: { alias },
  test: {
    environment: 'jsdom',
    // Worker threads instead of child processes: each test file still gets its own isolated environment, but a
    // worker starts faster (515 tests: 8.0 s instead of 8.5 to 9 s); files run in parallel on every core.
    pool: 'threads',
    globals: false,
    include: ['tests/**/*.test.{ts,tsx}'],
    setupFiles: ['tests/setup.ts'],
    coverage: { enabled: false },
    css: { modules: { classNameStrategy: 'non-scoped' } },
    restoreMocks: true,
    clearMocks: true,
    testTimeout: 15_000,
  },
});

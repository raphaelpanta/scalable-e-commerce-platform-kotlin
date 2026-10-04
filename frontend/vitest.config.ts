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

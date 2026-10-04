import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const gatewayPort = process.env['GATEWAY_PORT'] ?? '8080';

const alias = {
  '@domain': fileURLToPath(new URL('./src/domain', import.meta.url)),
  '@app': fileURLToPath(new URL('./src/app', import.meta.url)),
  '@api': fileURLToPath(new URL('./src/api', import.meta.url)),
  '@telemetry': fileURLToPath(new URL('./src/telemetry', import.meta.url)),
  '@ui': fileURLToPath(new URL('./src/ui', import.meta.url)),
};

// Static bundle served by nginx behind the gateway (research §1). Hashed asset names let the
// container cache /assets/ immutably while index.html stays no-store.
export default defineConfig({
  base: '/',
  plugins: [react()],
  resolve: { alias },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: `http://localhost:${gatewayPort}`,
        changeOrigin: false,
      },
    },
  },
});

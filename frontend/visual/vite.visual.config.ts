import { fileURLToPath, URL } from 'node:url';

import { defineConfig, mergeConfig } from 'vite';

import base from '../vite.config.ts';

// The `visual` build of the storefront (research §6): the real app behind the MSW browser worker,
// for the visual suite only. It extends vite.config.ts and changes what a harness needs:
// - `root` is visual/harness, so its index.html is the entry and every route falls back to it;
// - `publicDir` is visual/public (worker script and same-origin fixture images), so neither ever
//   reaches the production dist/ (vite.config.ts has no public dir of its own for them);
// - the preview server listens on port 80 inside the container, so the page origin is
//   `http://localhost`, the absolute base of the tests/msw handlers;
// - browser telemetry is off (no collector behind the harness).
// It is built and previewed rather than served by the dev server: the page then loads hashed
// /assets/ files like production, which the layout-shift and font-delay checks rely on.
const here = (path: string): string => fileURLToPath(new URL(path, import.meta.url));

export default mergeConfig(
  base,
  defineConfig({
    root: here('./harness'),
    publicDir: here('./public'),
    define: { 'import.meta.env.VITE_TELEMETRY': JSON.stringify('off') },
    build: {
      outDir: here('./.dist'),
      emptyOutDir: true,
      manifest: false,
    },
    preview: { host: 'localhost', port: 80, strictPort: true },
  }),
);

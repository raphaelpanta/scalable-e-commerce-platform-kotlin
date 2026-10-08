import { defineConfig, type Project } from '@playwright/test';

// Visual suite of the storefront (research §6, contracts/visual-baselines.md). It runs only inside
// mcr.microsoft.com/playwright:v1.63.0-noble through `npm run visual` (scripts/visual.mjs), so
// macOS hosts and CI render the same pixels; guard.ts refuses to run anywhere else.
//
// Six projects (360 / 768 / 1280 px × light / dark, height 900) carry the screenshot matrix of
// pages.spec.ts. quality.spec.ts sets its own widths, themes and media per case, so it runs once,
// in the 1280-light project only.
const WIDTHS = [360, 768, 1280] as const;
const THEMES = ['light', 'dark'] as const;
const QUALITY_PROJECT = '1280-light';

const projects: Project[] = WIDTHS.flatMap((width) =>
  THEMES.map((theme): Project => {
    const name = `${String(width)}-${theme}`;
    return {
      name,
      testIgnore: name === QUALITY_PROJECT ? [] : ['quality.spec.ts'],
      use: {
        viewport: { width, height: 900 },
        colorScheme: theme,
        reducedMotion: 'reduce',
      },
    };
  }),
);

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  outputDir: './test-results',
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  reporter: 'line',
  timeout: 30_000,
  expect: {
    timeout: 10_000,
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.001,
      threshold: 0.2,
      animations: 'disabled',
      caret: 'hide',
    },
  },
  use: {
    baseURL: 'http://localhost',
    trace: 'off',
  },
  projects,
  // The MSW-backed harness (visual/vite.visual.config.ts), built and served on port 80 so the
  // absolute `http://localhost` base of the tests/msw handlers matches the page origin.
  webServer: {
    command:
      'npx vite build -c visual/vite.visual.config.ts --logLevel error && npx vite preview -c visual/vite.visual.config.ts --logLevel error',
    url: 'http://localhost/mockServiceWorker.js',
    cwd: '..',
    reuseExistingServer: false,
    timeout: 180_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});

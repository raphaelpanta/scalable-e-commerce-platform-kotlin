import './guard.ts';

import { expect, test } from '@playwright/test';

import { openPage, PAGES } from './matrix.ts';

// The screenshot matrix of contracts/visual-baselines.md (FR-015, T045): every page of PAGES in
// every project of playwright.config.ts (360 / 768 / 1280 px × light / dark) = 48 baselines named
// `<page>-<width>-<theme>.png`. Time is frozen so countdowns and dates render the same pixels; the
// page is captured only after data, images and the brand fonts settled (matrix.ts `settle`).
const FROZEN_TIME = new Date('2026-01-15T10:00:00Z');

test.describe('visual baselines', () => {
  for (const visual of PAGES) {
    test(visual.name, async ({ page }, testInfo) => {
      await page.clock.install({ time: FROZEN_TIME });
      await openPage(page, visual);
      await expect(page).toHaveScreenshot(`${visual.name}-${testInfo.project.name}.png`, {
        fullPage: true,
      });
    });
  }
});

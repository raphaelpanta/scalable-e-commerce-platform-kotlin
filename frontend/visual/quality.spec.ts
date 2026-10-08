import './guard.ts';

import { AxeBuilder } from '@axe-core/playwright';
import { expect, type Page, test } from '@playwright/test';

import { CONSOLE_PAGES, harnessUrl, openPage, PAGES, pageNamed, settle } from './matrix.ts';
import { type A11yViolation, describeViolations } from '../acceptance/support/a11y.ts';

// The non-screenshot assertions of contracts/visual-baselines.md (T037): overflow, axe in both
// themes, forced colours, layout shift, control sizes, first-party requests only, no motion under
// reduced motion, and first text not held back by the brand fonts (SC-004). The suite runs once
// (the 1280-light project, playwright.config.ts); each case sets its own width, theme and media.
const ORIGIN = 'http://localhost';
const HEIGHT = 900;
const MIN_CONTROL = 44;
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22a', 'wcag22aa'];

test.describe('@quality', () => {
  test.describe('no horizontal overflow', () => {
    for (const visual of PAGES) {
      for (const width of [360, 640]) {
        test(`${visual.name} at ${String(width)}px`, async ({ page }) => {
          await page.setViewportSize({ width, height: HEIGHT });
          await openPage(page, visual);
          const { scrollWidth, clientWidth } = await page.evaluate(() => ({
            scrollWidth: document.documentElement.scrollWidth,
            clientWidth: document.documentElement.clientWidth,
          }));
          expect(scrollWidth, 'documentElement.scrollWidth <= clientWidth').toBeLessThanOrEqual(
            clientWidth,
          );
        });
      }
    }
  });

  test.describe('axe WCAG 2.2 A/AA', () => {
    for (const visual of [...PAGES, ...CONSOLE_PAGES]) {
      for (const colorScheme of ['light', 'dark'] as const) {
        test(`${visual.name} in ${colorScheme}`, async ({ page }) => {
          await page.emulateMedia({ colorScheme });
          await openPage(page, visual);
          const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
          const violations: A11yViolation[] = results.violations.map((violation) => ({
            id: violation.id,
            impact: violation.impact ?? 'minor',
            help: violation.help,
            targets: violation.nodes.flatMap((node) => node.target.map(String)),
          }));
          expect(violations, describeViolations(page.url(), violations)).toEqual([]);
        });
      }
    }
  });

  test.describe('forced colours', () => {
    test.use({ forcedColors: 'active' });
    for (const name of ['home', 'checkout']) {
      for (const width of [360, 1280]) {
        test(`${name} at ${String(width)}px`, async ({ page }) => {
          await page.setViewportSize({ width, height: HEIGHT });
          // Signed in, so the header carries a button (Sign out) on home too.
          await openPage(page, pageNamed(name), 'shopper');
          if (name === 'checkout') await ensureRadioChecked(page);
          const problems = await forcedColourProblems(page);
          problems.push(...(await focusRingProblems(page)));
          expect(problems, problems.join('\n')).toEqual([]);
        });
      }
    }
  });

  test.describe('layout shift', () => {
    for (const name of ['home', 'product']) {
      for (const width of [360, 1280]) {
        test(`${name} at ${String(width)}px: CLS < 0.1`, async ({ page }) => {
          await page.addInitScript(() => {
            const record = window as unknown as { __cls: number };
            record.__cls = 0;
            new PerformanceObserver((list) => {
              for (const entry of list.getEntries()) {
                const shift = entry as PerformanceEntry & {
                  value: number;
                  hadRecentInput: boolean;
                };
                if (!shift.hadRecentInput) record.__cls += shift.value;
              }
            }).observe({ type: 'layout-shift', buffered: true });
          });
          await page.setViewportSize({ width, height: HEIGHT });
          await openPage(page, pageNamed(name));
          await page.waitForTimeout(500);
          const cls = await page.evaluate(() => (window as unknown as { __cls: number }).__cls);
          expect(cls, 'cumulative layout shift').toBeLessThan(0.1);
        });
      }
    }
  });

  test('controls are at least 44 px on checkout at 360px', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: HEIGHT });
    await openPage(page, pageNamed('checkout'));
    await ensureRadioChecked(page);
    const problems = await smallControls(page, 'address step');
    await page.getByRole('button', { name: 'Continue to payment' }).click();
    await page.getByRole('radio').first().waitFor();
    await ensureRadioChecked(page);
    problems.push(...(await smallControls(page, 'payment step')));
    expect(problems, problems.join('\n')).toEqual([]);
  });

  test.describe('first-party requests only', () => {
    for (const name of ['home', 'product']) {
      test(`${name} at 1280px`, async ({ page, context }) => {
        const urls = new Set<string>();
        context.on('request', (request) => urls.add(request.url()));
        page.on('request', (request) => urls.add(request.url()));
        await openPage(page, pageNamed(name));
        await page.evaluate(() => {
          window.scrollTo(0, document.documentElement.scrollHeight);
        });
        await page.waitForLoadState('networkidle');
        const foreign = [...urls].filter(
          (url) => /^https?:/.test(url) && new URL(url).origin !== ORIGIN,
        );
        expect(foreign, `requests to other origins:\n${foreign.join('\n')}`).toEqual([]);
      });
    }
  });

  test.describe('no animations under reduced motion', () => {
    test.use({ reducedMotion: 'reduce' });
    for (const visual of PAGES) {
      test(visual.name, async ({ page }) => {
        await openPage(page, visual);
        const running = await page.evaluate(() =>
          document.getAnimations().map((animation) => {
            const target = (animation.effect as KeyframeEffect | null)?.target;
            const name =
              animation instanceof CSSAnimation
                ? animation.animationName
                : animation instanceof CSSTransition
                  ? animation.transitionProperty
                  : animation.id;
            return `${name} on ${target?.tagName.toLowerCase() ?? '?'}.${target?.className ?? ''}`;
          }),
        );
        expect(running, `running animations:\n${running.join('\n')}`).toEqual([]);
      });
    }
  });

  test.describe('first text is not held back by the brand fonts', () => {
    const FONT_DELAY_MS = 3_000;
    for (const name of ['home', 'product']) {
      test(`${name} at 360px`, async ({ page, context }) => {
        test.setTimeout(60_000);
        // Fonts reach the page through the MSW service worker, so the route sits on the context.
        const fontsDone: number[] = [];
        await context.route('**/assets/*.woff2', async (route) => {
          await new Promise((resolve) => setTimeout(resolve, FONT_DELAY_MS));
          const response = await route.fetch();
          await route.fulfill({ response });
          fontsDone.push(Date.now());
        });
        await page.addInitScript(() => {
          const record = window as unknown as { __firstText?: number };
          const check = (): boolean => {
            const root = document.getElementById('root');
            if (root === null || root.innerText.trim() === '') return false;
            record.__firstText = performance.now();
            return true;
          };
          const observer = new MutationObserver(() => {
            if (check()) observer.disconnect();
          });
          observer.observe(document, { childList: true, subtree: true, characterData: true });
        });
        await page.setViewportSize({ width: 360, height: HEIGHT });
        await page.goto(harnessUrl(pageNamed(name)));
        await settle(page);
        const timing = await page.evaluate(() => ({
          origin: performance.timeOrigin,
          fcp: performance.getEntriesByName('first-contentful-paint')[0]?.startTime,
          firstText: (window as unknown as { __firstText?: number }).__firstText,
        }));
        expect(timing.fcp, 'first-contentful-paint entry').toBeDefined();
        expect(timing.firstText, 'visible text in the DOM').toBeDefined();
        const firstFont = Math.min(...fontsDone);
        expect(
          timing.origin + (timing.fcp ?? Infinity),
          'first contentful paint happens before any font response completes',
        ).toBeLessThan(firstFont);
        expect(
          timing.origin + (timing.firstText ?? Infinity),
          'visible text is in the DOM before any font response completes',
        ).toBeLessThan(firstFont);
      });
    }
  });
});

/** Checks a radio when none is (the address picker may start without a default). */
async function ensureRadioChecked(page: Page): Promise<void> {
  const radios = page.getByRole('radio');
  if ((await radios.count()) === 0) return;
  if ((await page.locator('input[type="radio"]:checked').count()) === 0) {
    await radios.first().check({ force: true });
  }
}

/**
 * Forced colours: links, buttons (the primary one included) and the selected radio stay visible,
 * and no text is painted in the colour of what is behind it (text on Canvas at 1:1).
 */
async function forcedColourProblems(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const problems: string[] = [];
    const label = (element: Element): string =>
      `${element.tagName.toLowerCase()} "${element.textContent.trim().slice(0, 40)}"`;
    const backgroundOf = (element: Element | null): string => {
      for (let node = element; node !== null; node = node.parentElement) {
        const colour = getComputedStyle(node).backgroundColor;
        if (colour !== 'transparent' && colour !== 'rgba(0, 0, 0, 0)') return colour;
      }
      return getComputedStyle(document.documentElement).backgroundColor;
    };
    const hidden = (element: Element): boolean => {
      const box = element.getBoundingClientRect();
      if (box.width < 1 || box.height < 1) return true;
      for (let node: Element | null = element; node !== null; node = node.parentElement) {
        const style = getComputedStyle(node);
        if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') {
          return true;
        }
      }
      return false;
    };
    const rendered = (element: Element): boolean => {
      const style = getComputedStyle(element);
      return style.display !== 'none' && element.getClientRects().length > 0;
    };
    for (const element of document.querySelectorAll('a[href], button')) {
      if (!rendered(element) || element.classList.toString().includes('skip')) continue;
      if (hidden(element)) {
        problems.push(`${label(element)} is not visible`);
        continue;
      }
      const style = getComputedStyle(element);
      if (style.color === backgroundOf(element)) {
        problems.push(`${label(element)}: text colour equals its background (${style.color})`);
      }
      // The action buttons of the page (the primary one included) must still read as buttons.
      if (element.tagName === 'BUTTON' && element.closest('main') !== null) {
        const bordered = style.borderStyle !== 'none' && parseFloat(style.borderWidth) > 0;
        const filled = backgroundOf(element) !== backgroundOf(element.parentElement);
        if (!bordered && !filled) {
          problems.push(`${label(element)}: no border or fill marks the button`);
        }
      }
    }
    for (const radio of document.querySelectorAll('input[type="radio"]:checked')) {
      const box = radio.getBoundingClientRect();
      const style = getComputedStyle(radio);
      if (hidden(radio) || box.width < 10 || box.height < 10) {
        problems.push(
          `the selected radio is not visible (${String(box.width)}×${String(box.height)})`,
        );
      } else if (style.appearance === 'none' && parseFloat(style.borderWidth) === 0) {
        problems.push('the selected radio has no native look and no border');
      }
    }
    return problems;
  });
}

/** Tabs through the first stops of the page: each focused element shows an outline. */
async function focusRingProblems(page: Page): Promise<string[]> {
  const problems: string[] = [];
  await page.locator('body').focus();
  for (let stop = 0; stop < 12; stop += 1) {
    await page.keyboard.press('Tab');
    const ring = await page.evaluate(() => {
      const element = document.activeElement;
      if (element === null || element === document.body) return null;
      const style = getComputedStyle(element);
      return {
        name: `${element.tagName.toLowerCase()} "${element.textContent.trim().slice(0, 40)}"`,
        visible: style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) >= 1,
        outline: `${style.outlineStyle} ${style.outlineWidth} ${style.outlineColor}`,
      };
    });
    if (ring === null) break;
    if (!ring.visible) problems.push(`focus ring not visible on ${ring.name} (${ring.outline})`);
  }
  return problems;
}

/**
 * Buttons, primary navigation links and radios below 44 × 44 px. A radio's target is the input
 * together with its labels (clicking a label selects it).
 */
async function smallControls(page: Page, where: string): Promise<string[]> {
  const found = await page.evaluate((min) => {
    const small: string[] = [];
    const union = (boxes: DOMRect[]): { width: number; height: number } => ({
      width: Math.max(...boxes.map((box) => box.right)) - Math.min(...boxes.map((box) => box.left)),
      height:
        Math.max(...boxes.map((box) => box.bottom)) - Math.min(...boxes.map((box) => box.top)),
    });
    const controls = [
      ...document.querySelectorAll('button'),
      ...document.querySelectorAll('header nav a[href]'),
    ];
    const targets: Array<{ name: string; parts: Element[] }> = [
      ...controls.map((element) => ({
        name: `${element.tagName.toLowerCase()} "${element.textContent.trim().slice(0, 40)}"`,
        parts: [element],
      })),
      ...[...document.querySelectorAll<HTMLInputElement>('input[type="radio"]')].map((radio) => {
        const labels = [...(radio.labels ?? [])];
        const text = labels.map((label) => label.textContent.trim()).join(' ');
        return { name: `radio "${text.slice(0, 40)}"`, parts: [radio, ...labels] };
      }),
    ];
    for (const target of targets) {
      const parts = target.parts.filter((part) => part.getClientRects().length > 0);
      if (parts.length === 0) continue;
      const box = union(parts.map((part) => part.getBoundingClientRect()));
      if (box.width < min || box.height < min) {
        small.push(`${target.name} ${box.width.toFixed(0)}×${box.height.toFixed(0)}`);
      }
    }
    return small;
  }, MIN_CONTROL);
  return found.map((entry) => `${where}: ${entry}`);
}

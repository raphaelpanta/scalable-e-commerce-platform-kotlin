import { AxeBuilder } from '@axe-core/playwright';
import type { Page } from 'playwright';

// Accessibility audit of a rendered page (SC-006): critical and serious violations fail the
// scenario; moderate and minor ones are reported in the message only.
export const FAILING_IMPACTS: ReadonlySet<string> = new Set(['critical', 'serious']);

export type A11yViolation = {
  readonly id: string;
  readonly impact: string;
  readonly help: string;
  readonly targets: readonly string[];
};

export async function auditPage(page: Page): Promise<A11yViolation[]> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'])
    .analyze();
  return results.violations.map((violation) => ({
    id: violation.id,
    impact: violation.impact ?? 'minor',
    help: violation.help,
    targets: violation.nodes.flatMap((node) => node.target.map((target) => String(target))),
  }));
}

export function failing(violations: readonly A11yViolation[]): A11yViolation[] {
  return violations.filter((violation) => FAILING_IMPACTS.has(violation.impact));
}

export function describeViolations(url: string, violations: readonly A11yViolation[]): string {
  const lines = violations.map(
    (violation) =>
      `  [${violation.impact}] ${violation.id}: ${violation.help} (${violation.targets.slice(0, 3).join(', ')})`,
  );
  return `accessibility violations on ${url}:\n${lines.join('\n')}`;
}

/** Throws when the page has a critical or serious violation. */
export async function expectAccessible(page: Page): Promise<void> {
  const violations = failing(await auditPage(page));
  if (violations.length > 0) throw new Error(describeViolations(page.url(), violations));
}

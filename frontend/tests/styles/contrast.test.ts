import { readFileSync } from 'node:fs';
import path from 'node:path';

import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { contrastRatio } from './wcag';

const tokensCss = readFileSync(
  path.resolve(process.cwd(), 'src/ui/styles/tokens.css'),
  'utf8',
).replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the first block that starts at `open` (matching braces). */
function blockAfter(css: string, open: RegExp): string {
  const start = css.search(open);
  if (start < 0) throw new Error(`no block for ${String(open)}`);
  const first = css.indexOf('{', start);
  let depth = 0;
  for (let i = first; i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}' && --depth === 0) return css.slice(first + 1, i);
  }
  throw new Error('unbalanced braces');
}

function declarations(block: string): Map<string, string> {
  const map = new Map<string, string>();
  for (const match of block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    map.set(match[1] ?? '', (match[2] ?? '').trim());
  }
  return map;
}

const light = declarations(blockAfter(tokensCss, /:root\s*\{/));
const dark = declarations(
  blockAfter(blockAfter(tokensCss, /@media\s*\(prefers-color-scheme:\s*dark\)/), /:root\s*\{/),
);

type Kind = 'text' | 'ui';
interface Pair {
  fg: string;
  bg: string;
  kind: Kind;
}

const text = (fg: string, bg: string): Pair => ({ fg, bg, kind: 'text' });
const ui = (fg: string, bg: string): Pair => ({ fg, bg, kind: 'ui' });

/** The "Contrast matrix" of contracts/design-tokens.md, token names without the `--color-` prefix. */
const matrix: readonly Pair[] = [
  text('text', 'bg'),
  text('text', 'surface'),
  text('text', 'surface-raised'),
  text('text-muted', 'bg'),
  text('text-muted', 'surface'),
  text('text-muted', 'surface-raised'),
  text('text-muted', 'skeleton'),
  text('primary-contrast', 'primary'),
  text('primary-contrast', 'primary-hover'),
  text('primary', 'bg'),
  text('primary', 'surface'),
  text('primary', 'surface-raised'),
  text('primary', 'accent-subtle'),
  text('primary-hover', 'bg'),
  text('text', 'accent-subtle'),
  text('danger', 'bg'),
  text('danger', 'surface'),
  text('danger-contrast', 'danger'),
  text('success', 'bg'),
  text('success', 'surface'),
  text('warning', 'bg'),
  text('warning', 'surface'),
  text('notice-text', 'notice-bg'),
  ui('border-strong', 'bg'),
  ui('border-strong', 'surface'),
  ui('border-strong', 'surface-raised'),
  ui('focus', 'bg'),
  ui('focus', 'surface'),
  ui('focus', 'surface-raised'),
];

const byte = fc.integer({ min: 0, max: 255 });
const hexColour = fc
  .tuple(byte, byte, byte)
  .map(([r, g, b]) => `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`);

describe('WCAG contrast maths', () => {
  it('is symmetric', () => {
    fc.assert(
      fc.property(hexColour, hexColour, (a, b) => {
        expect(contrastRatio(a, b)).toBeCloseTo(contrastRatio(b, a), 10);
      }),
    );
  });

  it('stays within [1, 21]', () => {
    fc.assert(
      fc.property(hexColour, hexColour, (a, b) => {
        const ratio = contrastRatio(a, b);
        expect(ratio).toBeGreaterThanOrEqual(1);
        expect(ratio).toBeLessThanOrEqual(21 + 1e-9);
      }),
    );
  });

  it('is 21 for white against black', () => {
    expect(contrastRatio('#FFFFFF', '#000000')).toBeCloseTo(21, 10);
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 10);
  });

  it('is 1 for a colour against itself', () => {
    fc.assert(
      fc.property(hexColour, (a) => {
        expect(contrastRatio(a, a)).toBeCloseTo(1, 10);
      }),
    );
  });
});

describe('design tokens', () => {
  const colourTokens = [...light.keys()].filter((name) =>
    /^#[0-9a-f]{6}$/i.test(light.get(name) ?? ''),
  );

  it('defines the colour tokens of the contract in the light theme', () => {
    expect(light.has('--color-border-strong')).toBe(true);
    expect(colourTokens.length).toBeGreaterThanOrEqual(20);
  });

  it.each(colourTokens)('%s has a dark value', (name) => {
    expect(dark.has(name)).toBe(true);
  });

  const themes: readonly [string, Map<string, string>][] = [
    ['light', light],
    ['dark', dark],
  ];

  describe.each(themes)('%s theme contrast matrix', (_theme, values) => {
    it.each(matrix.map((pair) => [`${pair.fg} on ${pair.bg}`, pair] as const))(
      '%s',
      (_label, pair) => {
        const fg = values.get(`--color-${pair.fg}`);
        const bg = values.get(`--color-${pair.bg}`);
        expect(fg, `--color-${pair.fg}`).toBeDefined();
        expect(bg, `--color-${pair.bg}`).toBeDefined();
        expect(contrastRatio(fg ?? '', bg ?? '')).toBeGreaterThanOrEqual(
          pair.kind === 'text' ? 4.5 : 3,
        );
      },
    );
  });
});

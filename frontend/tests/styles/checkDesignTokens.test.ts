import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { afterAll, describe, expect, it } from 'vitest';

const script = path.resolve(process.cwd(), 'scripts/check-design-tokens.mjs');
const roots: string[] = [];

/** A temp project root with the given CSS files, keyed by path below `src/`. */
function fixture(files: Record<string, string>): string {
  const root = mkdtempSync(path.join(tmpdir(), 'design-tokens-'));
  roots.push(root);
  for (const [name, css] of Object.entries(files)) {
    const file = path.join(root, 'src', name);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, css);
  }
  return root;
}

function run(root: string): { status: number | null; output: string } {
  const result = spawnSync('node', [script, root], { encoding: 'utf8' });
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

afterAll(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true });
});

const clean = `
/* 12px and #fff in a comment are fine */
.card {
  margin: 0;
  width: 100%;
  padding: var(--space-4) var(--space-2);
  color: var(--color-text);
  background: transparent;
  border: var(--border-width) solid currentColor;
  font: inherit;
  line-height: 1.5;
  grid-template-columns: 1fr 2fr;
}
@media (min-width: 768px) {
  .card {
    gap: var(--space-6);
  }
}
`;

describe('check-design-tokens', () => {
  it('passes a clean tree and ignores tokens.css and fonts.css', () => {
    const root = fixture({
      'ui/components/Card.module.css': clean,
      'ui/styles/tokens.css': ':root { --color-bg: #ffffff; --space-1: 0.25rem; }',
      'ui/styles/fonts.css': "@font-face { src: url('x.woff2'); }",
      'ui/styles/global.css': 'body { margin: 0; color: var(--color-text); }',
    });
    expect(run(root)).toEqual({ status: 0, output: '' });
  });

  it.each([
    ['hex colour', 'color: #ff0000;', '#ff0000'],
    ['short hex colour', 'color: #f00;', '#f00'],
    ['rgb colour', 'background: rgb(0 0 0 / 45%);', 'rgb('],
    ['hsl colour', 'background: hsl(10 20% 30%);', 'hsl('],
    ['oklch colour', 'background: oklch(50% 0.1 20);', 'oklch('],
    ['named colour', 'color: red;', 'red'],
    ['px length', 'padding: 12px;', '12px'],
    ['rem length', 'max-width: 36rem;', '36rem'],
    ['em length', 'text-underline-offset: 0.3em;', '0.3em'],
    ['vh length', 'min-height: 100vh;', '100vh'],
    ['ch length', 'max-width: 65ch;', '65ch'],
    ['length inside a var fallback', 'gap: var(--space-2, 8px);', '8px'],
    ['https URL', "background: url('https://cdn.example.com/a.png');", 'https:'],
    ['http URL', "background: url('http://cdn.example.com/a.png');", 'http:'],
  ])('fails on a %s with file:line value', (_name, declaration, value) => {
    const root = fixture({
      'ui/components/Bad.module.css': `.ok {\n  margin: 0;\n}\n\n.bad {\n  ${declaration}\n}\n`,
    });
    const { status, output } = run(root);
    expect(status).toBe(1);
    expect(output).toContain(`src/ui/components/Bad.module.css:6 ${value}`);
  });

  it('scans global.css too and reports every offence', () => {
    const root = fixture({
      'ui/components/Card.module.css': clean,
      'ui/styles/global.css': 'a {\n  color: #123456;\n  margin: 4px;\n}\n',
    });
    const { status, output } = run(root);
    expect(status).toBe(1);
    expect(output).toContain('src/ui/styles/global.css:2 #123456');
    expect(output).toContain('src/ui/styles/global.css:3 4px');
  });

  it('does not flag class names that look like colour names', () => {
    const root = fixture({
      'ui/components/Names.module.css': '.red {\n  margin: 0;\n}\n.blue-card {\n  padding: 0;\n}\n',
    });
    expect(run(root).status).toBe(0);
  });
});

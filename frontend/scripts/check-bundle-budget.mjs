#!/usr/bin/env node
// First-load transfer budget of the storefront (feature 007, SC-003, research §7). Run after
// `npm run build`: sums what a first visit to the home page downloads (the entry chunk, its static
// imports, their stylesheets and the fonts those stylesheets reference) and compares the total with
// the baseline recorded in budget.json from `main` before the redesign.
//
//   node scripts/check-bundle-budget.mjs            fail when growth exceeds maxGrowthBytes
//   node scripts/check-bundle-budget.mjs --record   write the current size as the baseline
//
// Scripts and stylesheets count gzip-compressed (level 9); fonts count raw (woff2 is compressed).
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const distDir = path.join(frontendRoot, 'dist');
const manifestPath = path.join(distDir, '.vite', 'manifest.json');
const budgetPath = path.join(frontendRoot, 'budget.json');
const DEFAULT_MAX_GROWTH = 150 * 1024;

/** Every file a first visit to the entry loads, as paths relative to dist/. */
function firstLoadFiles(manifest, readText) {
  const entry = Object.values(manifest).find((chunk) => chunk.isEntry === true);
  if (entry === undefined) throw new Error('the Vite manifest has no entry chunk');
  const files = new Set();
  const visit = (chunk) => {
    if (files.has(chunk.file)) return;
    files.add(chunk.file);
    for (const css of chunk.css ?? []) files.add(css);
    for (const key of chunk.imports ?? []) {
      const imported = manifest[key];
      if (imported !== undefined) visit(imported);
    }
  };
  visit(entry);
  for (const css of [...files].filter((file) => file.endsWith('.css'))) {
    for (const match of readText(css).matchAll(/url\(\s*["']?([^"')]+\.woff2)["']?\s*\)/g)) {
      files.add(match[1].replace(/^\//, ''));
    }
  }
  return [...files].sort();
}

/** The transfer size of one file: gzip for text, raw for fonts. */
function transferBytes(file, content) {
  return file.endsWith('.woff2') ? content.length : gzipSync(content, { level: 9 }).length;
}

/** The verdict for a measured total against a recorded budget. */
function verdict(total, budget) {
  const limit = budget.baselineBytes + budget.maxGrowthBytes;
  return { ok: total <= limit, limit, growth: total - budget.baselineBytes };
}

function main() {
  if (!existsSync(manifestPath)) {
    process.stderr.write('budget: dist/.vite/manifest.json not found, run `npm run build` first\n');
    process.exit(2);
  }
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const files = firstLoadFiles(manifest, (file) => readFileSync(path.join(distDir, file), 'utf8'));
  const total = files.reduce(
    (sum, file) => sum + transferBytes(file, readFileSync(path.join(distDir, file))),
    0,
  );

  if (process.argv.includes('--record')) {
    const budget = { baselineBytes: total, maxGrowthBytes: DEFAULT_MAX_GROWTH };
    writeFileSync(budgetPath, `${JSON.stringify(budget, null, 2)}\n`);
    process.stdout.write(`budget: recorded baseline ${total} B over ${files.length} files\n`);
    return;
  }

  const budget = JSON.parse(readFileSync(budgetPath, 'utf8'));
  const result = verdict(total, budget);
  if (!result.ok) {
    process.stderr.write(
      `budget: first load is ${total} B, limit ${result.limit} B ` +
        `(baseline ${budget.baselineBytes} B + ${budget.maxGrowthBytes} B); growth ${result.growth} B\n` +
        files.map((file) => `  ${file}\n`).join(''),
    );
    process.exit(1);
  }
}

main();

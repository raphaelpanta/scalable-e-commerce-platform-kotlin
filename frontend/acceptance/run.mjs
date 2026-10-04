#!/usr/bin/env node
// `npm run acceptance`: the browser scenarios need a running platform. Like the JVM suite
// (acceptance/build.gradle.kts gated on GATEWAY_URL), the run is skipped with exit 0 when
// STOREFRONT_URL is unset; otherwise Cucumber.js runs with the tsx loader for the TypeScript steps.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storefrontUrl = process.env.STOREFRONT_URL ?? '';

if (storefrontUrl === '') {
  process.stdout.write(
    'acceptance: skipped (set STOREFRONT_URL=http://localhost:8080 to run the browser scenarios)\n',
  );
  process.exit(0);
}

const cucumber = path.join(
  frontendRoot,
  'node_modules',
  '@cucumber',
  'cucumber',
  'bin',
  'cucumber.js',
);
const result = spawnSync(
  process.execPath,
  ['--import', 'tsx', cucumber, '--config', 'acceptance/cucumber.mjs', ...process.argv.slice(2)],
  { cwd: frontendRoot, stdio: 'inherit', env: { ...process.env, CI: process.env.CI ?? '1' } },
);

process.exit(result.status ?? 1);

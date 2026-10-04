#!/usr/bin/env node
// Runs a command and prints its output only when it fails (Principle VIII: npm scripts, like
// `./gradlew -q verify`, are silent on success and elevate the full output on failure).
//
//   node scripts/quiet.mjs <command> [args...]
//
// QUIET=0 streams the output live (local debugging). The exit code is the command's.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [command, ...args] = process.argv.slice(2);
if (command === undefined) {
  process.stderr.write('usage: quiet.mjs <command> [args...]\n');
  process.exit(2);
}

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const binDir = path.join(frontendRoot, 'node_modules', '.bin');
const env = {
  ...process.env,
  CI: process.env.CI ?? '1',
  NO_COLOR: '1',
  FORCE_COLOR: '0',
  PATH: `${binDir}${path.delimiter}${process.env.PATH ?? ''}`,
};

const live = process.env.QUIET === '0';
const result = spawnSync(command, args, {
  cwd: frontendRoot,
  env,
  stdio: live ? 'inherit' : ['ignore', 'pipe', 'pipe'],
  shell: false,
  maxBuffer: 64 * 1024 * 1024,
});

const status = result.status ?? 1;
if (!live && status !== 0) {
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.error) process.stderr.write(`${result.error.message}\n`);
}
process.exit(status);

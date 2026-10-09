#!/usr/bin/env node
// Runs the visual suite (frontend/visual, research §6) inside mcr.microsoft.com/playwright:v1.63.0-noble
// through podman, or docker when podman does not answer, so every host renders the same pixels:
//
//   npm run visual [-- <playwright test args>]     e.g. -- --grep @quality, -- --update-snapshots
//
// Output follows Principle VIII: silent on success; on failure only the failures and the paths of
// their actual/diff images (rewritten to host paths). `--list`, `--help` and QUIET=0 stream live.
//
// node_modules: the frontend directory is bind-mounted at /work, but the host's node_modules holds
// macOS builds of the native packages (rolldown, esbuild, lightningcss…), which cannot load on
// linux. A named volume is mounted over /work/node_modules, so the container never sees (or
// touches) the host's copy. The volume name carries the hash of package-lock.json: the first run
// of a lock fills it with `npm ci` (linux binaries), later runs reuse it as is, and the volumes of
// older locks are removed. Only volumes with this script's prefix are ever removed.
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const IMAGE = 'mcr.microsoft.com/playwright:v1.63.0-noble';
const VOLUME_PREFIX = 'storefront-visual-node-modules-';
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);

// An engine counts only when it answers `info`: the CI runner image ships a podman binary that cannot run containers
// inside the runner container (overlay over overlayfs) next to the docker CLI that reaches the host engine through the
// mounted socket. CONTAINER_ENGINE=podman|docker skips the probe.
function available(command) {
  const probe = spawnSync(command, ['info'], { stdio: 'ignore', timeout: 30_000 });
  return probe.status === 0;
}

const engine = process.env.CONTAINER_ENGINE || ['podman', 'docker'].find(available);
if (engine === undefined) {
  process.stderr.write('visual: neither podman nor docker answers (podman info, docker info)\n');
  process.exit(2);
}

// The generated API types (src/api/generated, git-ignored) come from the host, like `npm run build`.
const generated = spawnSync(process.execPath, ['scripts/generate-api.mjs', '--ensure'], {
  cwd: frontendRoot,
  encoding: 'utf8',
});
if (generated.status !== 0) {
  process.stderr.write(`${generated.stdout}${generated.stderr}`);
  process.exit(generated.status ?? 1);
}

const lockHash = createHash('sha256')
  .update(readFileSync(path.join(frontendRoot, 'package-lock.json')))
  .digest('hex')
  .slice(0, 16);
const volume = `${VOLUME_PREFIX}${lockHash}`;

const listed = spawnSync(engine, ['volume', 'ls', '--format', '{{.Name}}'], { encoding: 'utf8' });
for (const name of (listed.stdout ?? '').split('\n')) {
  if (name.startsWith(VOLUME_PREFIX) && name !== volume) {
    spawnSync(engine, ['volume', 'rm', name], { stdio: 'ignore' });
  }
}

// Inside the container: install linux node_modules into the volume when its lock differs, then run
// Playwright with the forwarded arguments.
const inContainer = [
  'set -e',
  'if ! cmp -s package-lock.json node_modules/.visual-lock; then',
  '  npm ci --no-audit --no-fund --loglevel=error >/tmp/npm-ci.log 2>&1 || { cat /tmp/npm-ci.log; exit 1; }',
  '  cp package-lock.json node_modules/.visual-lock',
  'fi',
  'exec npx playwright test -c visual/playwright.config.ts "$@"',
].join('\n');

const command = [
  'run',
  '--rm',
  '--ipc=host',
  '-e',
  'PLAYWRIGHT_IN_CONTAINER=1',
  '-e',
  'PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1',
  '-e',
  'CI=1',
  '-e',
  'FORCE_COLOR=0',
  '-v',
  `${frontendRoot}:/work`,
  '-v',
  `${volume}:/work/node_modules`,
  '-w',
  '/work',
  IMAGE,
  'sh',
  '-c',
  inContainer,
  'visual',
  ...args,
];

const live =
  process.env.QUIET === '0' || args.some((arg) => ['--list', '--help', '-h'].includes(arg));
const child = spawn(engine, command, { stdio: live ? 'inherit' : ['ignore', 'pipe', 'pipe'] });

const chunks = [];
if (!live) {
  child.stdout.on('data', (chunk) => chunks.push(chunk));
  child.stderr.on('data', (chunk) => chunks.push(chunk));
}

child.on('close', (code) => {
  const status = code ?? 1;
  if (!live && status !== 0) {
    const output = Buffer.concat(chunks)
      .toString('utf8')
      .split('\n')
      .filter((line) => !/^\s*\[\d+\/\d+\]/.test(line))
      .filter((line) => !/^Running \d+ tests? using \d+ workers?/.test(line))
      .map((line) => line.replaceAll('/work/', `${frontendRoot}/`))
      .join('\n')
      .replace(/\n{3,}/g, '\n\n');
    process.stdout.write(output.endsWith('\n') ? output : `${output}\n`);
  }
  process.exit(status);
});

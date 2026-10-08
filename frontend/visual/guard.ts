// Every visual spec imports this first. Screenshots and layout measurements are only comparable
// when they come from mcr.microsoft.com/playwright:v1.63.0-noble (contracts/visual-baselines.md),
// so the suite refuses to run anywhere else: use `npm run visual` (scripts/visual.mjs).
if (process.env['PLAYWRIGHT_IN_CONTAINER'] !== '1') {
  throw new Error(
    'The visual suite runs only inside mcr.microsoft.com/playwright:v1.63.0-noble: use `npm run visual`.',
  );
}

export {};

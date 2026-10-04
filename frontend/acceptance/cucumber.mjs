// Cucumber.js configuration of the storefront acceptance suite (run through acceptance/run.mjs,
// which registers the `tsx` loader and skips the suite when STOREFRONT_URL is unset).
const tags = process.env.CUCUMBER_TAGS ?? '';

export default {
  paths: ['acceptance/features/**/*.feature'],
  import: ['acceptance/support/**/*.ts', 'acceptance/steps/**/*.ts'],
  format: ['summary'],
  strict: true,
  publish: false,
  ...(tags === '' ? {} : { tags }),
};

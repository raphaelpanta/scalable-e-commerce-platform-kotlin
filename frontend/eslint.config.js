// ESLint flat config of the storefront (constitution VII: warnings are errors in CI, so every
// script runs with --max-warnings 0). Layering rules of data-model.md §1 are enforced here:
// src/domain is pure, src/app imports domain only, src/api and src/ui are the edges.
import eslintJs from '@eslint/js';
import prettier from 'eslint-config-prettier';
import { flatConfigs as importXFlatConfigs } from 'eslint-plugin-import-x';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import { config as defineConfig, configs as tsConfigs } from 'typescript-eslint';

const browserGlobalsForbiddenInPureLayers = [
  'window',
  'document',
  'navigator',
  'location',
  'history',
  'fetch',
  'localStorage',
  'sessionStorage',
  'XMLHttpRequest',
  'setTimeout',
  'setInterval',
  'requestAnimationFrame',
];

const forbiddenSyntax = [
  {
    selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
    message: 'dangerouslySetInnerHTML is forbidden: render text as text (FR-015).',
  },
  {
    selector: "MemberExpression[property.name='dangerouslySetInnerHTML']",
    message: 'dangerouslySetInnerHTML is forbidden: render text as text (FR-015).',
  },
  {
    selector: "Identifier[name='localStorage']",
    message:
      'localStorage is forbidden (constitution VII); sessionStorage only for the listed ids.',
  },
  {
    selector: "MemberExpression[property.name='localStorage']",
    message:
      'localStorage is forbidden (constitution VII); sessionStorage only for the listed ids.',
  },
  {
    selector: "CallExpression[callee.name='eval']",
    message: 'eval is forbidden (CSP without unsafe-eval).',
  },
  {
    selector: "MemberExpression[property.name='innerHTML']",
    message: 'innerHTML is forbidden: render text as text (FR-015).',
  },
  {
    selector: "MemberExpression[property.name='outerHTML']",
    message: 'outerHTML is forbidden: render text as text (FR-015).',
  },
  {
    selector: "CallExpression[callee.property.name='insertAdjacentHTML']",
    message: 'insertAdjacentHTML is forbidden: render text as text (FR-015).',
  },
  {
    selector: "NewExpression[callee.name='Function']",
    message: 'new Function is forbidden (CSP without unsafe-eval).',
  },
];

const layerAliases = '@{domain,app,api,telemetry,ui}/**';

export default defineConfig(
  {
    ignores: ['node_modules/', 'dist/', 'build/', 'src/api/generated/', 'public/'],
  },
  eslintJs.configs.recommended,
  ...tsConfigs.strictTypeChecked,
  ...tsConfigs.stylisticTypeChecked,
  importXFlatConfigs.recommended,
  importXFlatConfigs.typescript,
  {
    files: ['**/*.{ts,tsx,mts,cts,js,mjs}'],
    languageOptions: {
      parserOptions: {
        projectService: {
          allowDefaultProject: ['eslint.config.js', 'scripts/*.mjs', 'acceptance/*.mjs'],
        },
        tsconfigRootDir: import.meta.dirname,
      },
      globals: { ...globals.browser, ...globals.es2023 },
    },
    settings: {
      'import-x/resolver': {
        typescript: { project: './tsconfig.json', alwaysTryTypes: true },
        node: true,
      },
      react: { version: 'detect' },
    },
    rules: {
      'no-restricted-syntax': ['error', ...forbiddenSyntax],
      'no-console': 'error',
      eqeqeq: ['error', 'always'],
      curly: ['error', 'multi-line'],
      'import-x/no-default-export': 'error',
      'import-x/no-cycle': 'error',
      'import-x/no-named-as-default-member': 'off',
      'import-x/order': [
        'error',
        {
          groups: ['builtin', 'external', 'internal', ['parent', 'sibling', 'index']],
          pathGroups: [{ pattern: layerAliases, group: 'internal' }],
          pathGroupsExcludedImportTypes: ['builtin'],
          'newlines-between': 'always',
          alphabetize: { order: 'asc', caseInsensitive: true },
        },
      ],
      '@typescript-eslint/consistent-type-definitions': ['error', 'type'],
      // Bracket access marks reads from index signatures (process.env, generated records).
      '@typescript-eslint/dot-notation': 'off',
      '@typescript-eslint/array-type': [
        'error',
        { default: 'array-simple', readonly: 'array-simple' },
      ],
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
      '@typescript-eslint/explicit-module-boundary-types': 'error',
      '@typescript-eslint/no-unnecessary-condition': 'error',
      '@typescript-eslint/switch-exhaustiveness-check': [
        'error',
        { considerDefaultExhaustiveForUnions: true },
      ],
      '@typescript-eslint/restrict-template-expressions': [
        'error',
        { allowNumber: true, allowBoolean: false, allowNullish: false, allowAny: false },
      ],
      '@typescript-eslint/no-misused-promises': [
        'error',
        { checksVoidReturn: { attributes: false } },
      ],
    },
  },
  {
    files: ['**/*.{jsx,tsx}'],
    ...react.configs.flat.recommended,
  },
  {
    files: ['**/*.{jsx,tsx}'],
    ...react.configs.flat['jsx-runtime'],
  },
  {
    files: ['**/*.{jsx,tsx}'],
    ...jsxA11y.flatConfigs.strict,
  },
  {
    files: ['**/*.{jsx,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs['recommended-latest'].rules,
      'react/prop-types': 'off',
      'react/no-danger': 'error',
      'react/jsx-no-target-blank': 'error',
      'react/self-closing-comp': 'error',
      'react/jsx-no-useless-fragment': 'error',
      'react/function-component-definition': [
        'error',
        { namedComponents: 'function-declaration', unnamedComponents: 'arrow-function' },
      ],
    },
  },
  {
    // src/domain: pure TypeScript, no other layer and no browser globals.
    files: ['src/domain/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@app/*',
                '@api/*',
                '@ui/*',
                '@telemetry/*',
                '**/src/app/**',
                '**/src/api/**',
                '**/src/ui/**',
                '**/src/telemetry/**',
                '../app/*',
                '../api/*',
                '../ui/*',
                '../telemetry/*',
                'react',
                'react-*',
                '@tanstack/*',
                'openapi-fetch',
              ],
              message:
                'src/domain is pure: it imports nothing from app, api, ui, telemetry or any framework.',
            },
          ],
        },
      ],
      'no-restricted-globals': ['error', ...browserGlobalsForbiddenInPureLayers],
    },
  },
  {
    // src/app: use cases; imports domain only. The api edge is reached through injected ports and
    // React/TanStack hooks are the one framework surface allowed (useSession).
    files: ['src/app/**/*.ts', 'src/app/**/*.tsx'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@ui/*',
                '@telemetry/*',
                '**/src/ui/**',
                '**/src/telemetry/**',
                '@api/!(generated)/**',
                '@api/client',
                '@api/problem',
                '@api/session',
              ],
              message:
                'src/app imports domain only (plus type-only imports of generated contracts); api is injected as a port.',
            },
          ],
        },
      ],
      'no-restricted-globals': ['error', 'localStorage'],
    },
  },
  {
    files: ['src/telemetry/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@ui/*',
                '@app/*',
                '@api/*',
                '**/src/ui/**',
                '**/src/app/**',
                '**/src/api/**',
              ],
              message: 'src/telemetry depends on domain only.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['tests/**/*.{ts,tsx}', 'pact/**/*.ts', 'acceptance/**/*.ts'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/explicit-module-boundary-types': 'off',
      'react/display-name': 'off',
    },
  },
  {
    files: [
      'acceptance/**/*.ts',
      'acceptance/**/*.mjs',
      'scripts/**/*.mjs',
      '*.config.ts',
      'eslint.config.js',
      'pact/**/*.ts',
      'src/**/*.d.ts',
    ],
    languageOptions: { globals: { ...globals.node } },
    rules: {
      'import-x/no-default-export': 'off',
      'no-console': 'off',
      'no-restricted-syntax': 'off',
    },
  },
  {
    files: ['**/*.{js,mjs}'],
    ...tsConfigs.disableTypeChecked,
  },
  prettier,
);

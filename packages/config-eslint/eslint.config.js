// TypeScript 7 (the Go-native compiler) is not supported by any released
// typescript-eslint — both `latest` (8.69.0) and `canary` cap their peer range
// at `typescript@<6.1.0`, and the guard is a hard error, not a suppressible
// warning (`warnOnUnsupportedTypeScriptVersion: false` does not bypass it).
//
// typescript-eslint is therefore removed. Because stock ESLint cannot parse TS
// at all without it, `.ts`/`.tsx` are no longer linted -- type checking is
// carried entirely by `tsc --noEmit` (run via each app's `typecheck` script).
// Restore this block when typescript-eslint ships TS 7 support, or adopt a
// linter that parses TS natively (oxlint) if that lands first.
import react from 'eslint-plugin-react';
import prettier from 'eslint-config-prettier';
import globals from 'globals';

const sharedIgnores = [
  '**/node_modules/**',
  '**/dist/**',
  '**/build/**',
  '**/target/**',
  '**/*.jar',
  '**/coverage/**',
  '**/playwright-report/**',
  '**/test-results/**',
  'apps/cli-server/src/main/resources/public/**',
  // Vite writes these next to vite.config.ts while bundling the config and
  // deletes them immediately; linting concurrently with a build would otherwise
  // race and fail with ENOENT.
  '**/*.timestamp-*.mjs',
];

export default [
  { ignores: sharedIgnores },
  {
    files: ['**/*.{js,mjs,cjs}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { react },
    settings: { react: { pragma: 'h', version: 'detect' } },
    rules: {
      ...react.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      'no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  prettier,
];

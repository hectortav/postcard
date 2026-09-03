import tseslint from '@typescript-eslint/eslint-plugin';
import tsparser from '@typescript-eslint/parser';
import react from 'eslint-plugin-react';
import prettier from 'eslint-config-prettier';
import stylex from '@stylexjs/eslint-plugin';
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
];

export default [
  {
    ignores: sharedIgnores,
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsparser,
      parserOptions: {
        ecmaVersion: 2022,
        sourceType: 'module',
        ecmaFeatures: { jsx: true },
      },
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: {
      '@typescript-eslint': tseslint,
      react,
      '@stylexjs': stylex,
    },
    settings: { react: { pragma: 'h', version: 'detect' } },
    rules: {
      ...tseslint.configs.recommended.rules,
      ...react.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  prettier,
];

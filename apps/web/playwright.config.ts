import { defineConfig } from '@playwright/test';

function requireBaseUrl(): string {
  const url = process.env.POSTCARD_E2E_URL;
  if (url) return url;
  if (process.env.CI) {
    throw new Error(
      'POSTCARD_E2E_URL is not set. These specs run against a postcard server started by the ' +
        'workflow; see the e2e jobs in .github/workflows/build.yml.',
    );
  }
  // Locally, fall back to the Vite dev server so `pnpm dev` + `pnpm e2e` still works.
  return 'http://localhost:5173';
}

export default defineConfig({
  testDir: './tests/playwright',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  // One retry, so `trace: 'on-first-retry'` below can actually produce a trace: with
  // zero retries a first retry never happens and the setting was inert.
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    // These specs need a running postcard, which the harness starts and points at through
    // POSTCARD_E2E_URL. Falling back silently to the Vite dev port produced a wall of
    // connection-refused errors instead of saying what was missing.
    baseURL: requireBaseUrl(),
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
    { name: 'webkit', use: { browserName: 'webkit' } },
  ],
});

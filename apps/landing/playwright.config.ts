import { defineConfig } from '@playwright/test';

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
    // The site is built with `base: '/postcard/'` for GitHub Pages, so `vite preview` serves
    // it under that prefix and the root is a 404. This suite never ran in CI, which is how a
    // baseURL pointing at the wrong path went unnoticed.
    baseURL: process.env.POSTCARD_LANDING_E2E_URL ?? 'http://localhost:4173/postcard/',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: process.env.POSTCARD_LANDING_E2E_URL
    ? undefined
    : {
        command: 'pnpm preview --port 4173',
        url: 'http://localhost:4173',
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
});

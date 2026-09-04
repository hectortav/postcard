import { test, expect } from '@playwright/test';

// Tabs in apps/web/src/App.tsx are <div role="tab"> rather than <button>
// (see commit 11dd932). The selector therefore matches the ARIA role, not
// the implicit-button role. The dashboard never shows the PinLockScreen in
// this spec because the cli-server is started without `--pin`.
test('clipboard text round-trips between tabs', async ({ page, context }) => {
  const a = page; await a.goto('/');
  await a.getByRole('tab', { name: 'Clipboard' }).click();
  const b = await context.newPage(); await b.goto('/');
  await b.getByRole('tab', { name: 'Clipboard' }).click();
  await a.locator('textarea').fill('round-trip');
  await expect(b.locator('textarea')).toHaveValue('round-trip', { timeout: 3_000 });
});

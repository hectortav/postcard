import { test, expect } from '@playwright/test';

test('file dropped by host appears in mobile client', async ({ browser }) => {
  const ctx1 = await browser.newContext();
  const host = await ctx1.newPage();
  const mobile = await (await browser.newContext({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' })).newPage();
  await host.goto('/');
  await mobile.goto('/');
  // Wait for the dashboard to mount (tablist is unconditionally present
  // once the App renders). The "no files yet" copy is only shown on a
  // truly empty FileList; the second browser run in the same CI job may
  // see a file the first run uploaded, which would race this assertion.
  await expect(mobile.getByRole('tablist')).toBeVisible();
  await host.setInputFiles('input[type=file]', { name: 'hello.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') });
  await expect(mobile.getByRole('link', { name: 'hello.txt' })).toBeVisible({ timeout: 5_000 });
});

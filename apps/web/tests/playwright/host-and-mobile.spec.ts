import { test, expect } from '@playwright/test';

test('file dropped by host appears in mobile client', async ({ browser }) => {
  // Unique per invocation. Every spec in this suite shares one server, and this one runs
  // under both browser projects and can be retried, so a fixed name leaves two or three
  // identical rows on screen and the locator below matches all of them.
  const name = `hello-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.txt`;
  const ctx1 = await browser.newContext();
  const host = await ctx1.newPage();
  const mobile = await (
    await browser.newContext({
      userAgent:
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
    })
  ).newPage();
  await host.goto('/');
  await mobile.goto('/');
  // Wait for the dashboard to mount (tablist is unconditionally present once the App
  // renders). The "no files yet" copy only shows on a truly empty list, and by the time this
  // runs the server usually holds files from earlier specs.
  await expect(mobile.getByRole('tablist')).toBeVisible();
  await host.setInputFiles('input[type=file]', {
    name,
    mimeType: 'text/plain',
    buffer: Buffer.from('hello'),
  });
  await expect(mobile.getByRole('link', { name })).toBeVisible({ timeout: 10_000 });
});

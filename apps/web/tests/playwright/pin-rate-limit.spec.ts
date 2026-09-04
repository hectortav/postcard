import { test, expect } from '@playwright/test';

// The cli-server is started with `--pin 1234` in the e2e-pin CI job.
// The bind URL is exported to SENDME_E2E_URL and includes the fragment
// `&pin=1234` — the spec navigates straight to that absolute URL so the
// browser sees the fragment, which is what gates the PinLockScreen.
//
// Flow under test: three consecutive wrong PINs arm the 15-minute
// lockout. The 3rd attempt returns 429 (not 401), so the UI shows the
// countdown text instead of the "did not match" error.
const E2E_URL = process.env.SENDME_E2E_URL ?? 'http://localhost:5173/';

test('3 wrong PINs lock the IP; countdown appears on the 3rd', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(E2E_URL);

  // The PinLockScreen is gated on the URL fragment containing `pin=`.
  await expect(page.getByTestId('pin-box-0')).toBeVisible({ timeout: 5_000 });

  // Two wrong PINs first — each returns 401, the UI clears the boxes
  // and shows the "did not match" error.
  for (let attempt = 0; attempt < 2; attempt++) {
    for (let i = 0; i < 4; i++) {
      await page.getByTestId(`pin-box-${i}`).fill('0');
    }
    await expect(page.getByText(/did not match/i)).toBeVisible({ timeout: 5_000 });
  }

  // Third wrong PIN arms the 15-minute lockout. The server returns 429
  // and the UI shows the countdown text instead of "did not match".
  for (let i = 0; i < 4; i++) {
    await page.getByTestId(`pin-box-${i}`).fill('0');
  }
  await expect(page.getByText(/remaining/i)).toBeVisible({ timeout: 5_000 });
  // While locked, the inputs are disabled.
  for (let i = 0; i < 4; i++) {
    await expect(page.getByTestId(`pin-box-${i}`)).toBeDisabled();
  }
});

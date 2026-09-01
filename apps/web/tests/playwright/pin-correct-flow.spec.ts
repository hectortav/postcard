import { test, expect } from '@playwright/test';

// The cli-server is started with `--pin 1234` in the e2e-pin CI job
// (see .github/workflows/build.yml `e2e-pin`). The bind URL is exported
// to POSTCARD_E2E_URL and includes the fragment `&pin=1234`. The fragment
// is the client-side signal that gates the PinLockScreen on mount; the
// actual unlock is POST /api/pin/verify, where the server re-derives
// the AES key from (secret, pin, salt) and compares it to its stored
// expected key. The fragment is never sent to the server, so we
// navigate the browser to the absolute URL with the fragment
// (Playwright strips the fragment when resolving a relative `goto('/')`
// against baseURL).
const E2E_URL = process.env.POSTCARD_E2E_URL ?? 'http://localhost:5173/';
const PIN = '1234';

test('correct PIN unlocks the dashboard and files round-trip', async ({ browser }) => {
  // Host: a fresh context. The server's gating and rate limiter are per-IP,
  // so each context gets its own quota.
  const hostCtx = await browser.newContext();
  const host = await hostCtx.newPage();
  await host.goto(E2E_URL);
  // The fragment on the URL triggers the PinLockScreen on mount.
  await expect(host.getByTestId('pin-box-0')).toBeVisible({ timeout: 5_000 });

  // Type the PIN through the UI. The 4th digit auto-submits.
  for (let i = 0; i < 4; i++) {
    await host.getByTestId(`pin-box-${i}`).fill(PIN[i]!);
  }
  // 200 → onVerified → App flips pinUnlocked → dashboard renders. We
  // assert on the tablist (always present) rather than the empty-state
  // copy, because the second browser run in the same CI job shares the
  // server's FileStore and may see the file the first run uploaded.
  await expect(host.getByRole('tablist')).toBeVisible({ timeout: 10_000 });

  // Mobile: also a fresh context, different IP. It "scans" the QR.
  const mobileCtx = await browser.newContext({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' });
  const mobile = await mobileCtx.newPage();
  await mobile.goto(E2E_URL);
  await expect(mobile.getByTestId('pin-box-0')).toBeVisible({ timeout: 5_000 });
  for (let i = 0; i < 4; i++) {
    await mobile.getByTestId(`pin-box-${i}`).fill(PIN[i]!);
  }
  await expect(mobile.getByRole('tablist')).toBeVisible({ timeout: 10_000 });

  // Host drops a file; mobile should see it.
  await host.setInputFiles('input[type=file]', { name: 'pin-hello.txt', mimeType: 'text/plain', buffer: Buffer.from('hi') });
  await expect(mobile.getByRole('link', { name: 'pin-hello.txt' })).toBeVisible({ timeout: 5_000 });
});

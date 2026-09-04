import { test, expect } from '@playwright/test';

// The cli-server is started with `--pin 5678` in the e2e-pin CI job.
// The bind URL is exported to SENDME_E2E_URL and includes the fragment
// `&pin=5678` — the spec navigates straight to that absolute URL so the
// browser sees the fragment, which is what gates the PinLockScreen.
//
// The fragment is never sent to the server, so the URL also works as
// the API base for `/api/pin/verify` and `/api/files` requests.
const E2E_URL = process.env.SENDME_E2E_URL ?? 'http://localhost:5173/';

test('correct PIN reveals the dashboard and files round-trip', async ({ browser }) => {
  const hostCtx = await browser.newContext();
  const host = await hostCtx.newPage();
  // Host navigates to the root URL (no fragment): its dashboard is
  // un-gated because the server is on the same machine as the bind URL.
  const hostUrl = E2E_URL.split('#')[0]!;
  await host.goto(hostUrl);
  await expect(host.getByText(/no files yet/i)).toBeVisible({ timeout: 5_000 });

  // The mobile "scans" the QR, so its URL has the pin fragment.
  const mobileCtx = await browser.newContext({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' });
  const mobile = await mobileCtx.newPage();
  await mobile.goto(E2E_URL);
  // The fragment is read on mount; the PinLockScreen is shown because
  // the URL has &pin=... in the fragment.
  await expect(mobile.getByTestId('pin-box-0')).toBeVisible({ timeout: 5_000 });

  // Type the correct PIN. The component auto-submits on the 4th digit.
  for (let i = 0; i < 4; i++) {
    await mobile.getByTestId(`pin-box-${i}`).fill('5678'[i]!);
  }

  // After verify succeeds the dashboard renders, including the file list.
  await expect(mobile.getByText(/no files yet/i)).toBeVisible({ timeout: 5_000 });

  // Host drops a file; mobile should see it.
  await host.setInputFiles('input[type=file]', { name: 'pin-hello.txt', mimeType: 'text/plain', buffer: Buffer.from('hi') });
  await expect(mobile.getByRole('link', { name: 'pin-hello.txt' })).toBeVisible({ timeout: 5_000 });
});

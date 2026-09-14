import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

const SECRET = 'super-secret-payload-that-must-round-trip';
// Unique per run. These specs share one server with every other spec and with the other
// browser project, so a fixed filename leaves several identical rows on screen and the row
// locator matches more than one of them.
const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const wireName = `wire-check-${stamp}.txt`;
const roundTripName = `round-trip-${stamp}.txt`;

test('the ciphertext on the wire is not the plaintext', async ({ request }) => {
  const up = await request.post('/api/upload', {
    multipart: { file: { name: wireName, mimeType: 'text/plain', buffer: Buffer.from(SECRET) } },
  });
  const { id } = await up.json();
  const res = await request.get(`/api/download/${id}`);
  expect(res.status()).toBe(200);
  expect((await res.body()).toString('utf8')).not.toContain(SECRET);
});

test('the dashboard decrypts a download back to the original bytes', async ({ page, baseURL }) => {
  // The test that was missing. The old suite asserted only that the bytes on the wire were
  // not plaintext -- which passed perfectly well against a browser that could not decrypt
  // them either, and did not. Receivers were being handed unreadable files.
  const up = await page.request.post('/api/upload', {
    multipart: {
      file: { name: roundTripName, mimeType: 'text/plain', buffer: Buffer.from(SECRET) },
    },
  });
  expect(up.ok()).toBeTruthy();

  await page.goto(baseURL!);
  await expect(page.getByRole('tablist')).toBeVisible();

  const row = page.getByRole('button', { name: new RegExp(roundTripName.replace(/\./g, '\\.')) });
  await expect(row).toBeVisible({ timeout: 10_000 });

  const [download] = await Promise.all([page.waitForEvent('download'), row.click()]);
  expect(download.suggestedFilename()).toBe(roundTripName);
  const path = await download.path();
  expect(readFileSync(path!, 'utf8')).toBe(SECRET);
});

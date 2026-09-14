import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

// Needs a server started with --encrypt --encrypt-owner --pin 1234.
const SECRET = 'pin-protected-payload';
const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const name = `pin-round-trip-${stamp}.txt`;

test('a receiver enters the PIN, then decrypts the file', async ({ page, baseURL }) => {
  // With a PIN armed the AES key is not the secret in the link: it is PBKDF2(PIN, SHA-256(
  // secret)), derived in the browser and never transmitted. This exercises that derivation
  // against the key the server independently derived for the same pair.
  await page.goto(baseURL!);

  const first = page.getByRole('textbox').first();
  await expect(first).toBeVisible({ timeout: 10_000 });
  await first.fill('1');
  await page.keyboard.type('234');

  await expect(page.getByRole('tablist')).toBeVisible({ timeout: 30_000 });

  await page.request.post('/api/upload', {
    multipart: { file: { name, mimeType: 'text/plain', buffer: Buffer.from(SECRET) } },
  });

  const row = page.getByRole('button', { name: new RegExp(name.replace(/\./g, '\\.')) });
  await expect(row).toBeVisible({ timeout: 10_000 });

  const [download] = await Promise.all([page.waitForEvent('download'), row.click()]);
  const path = await download.path();
  expect(readFileSync(path!, 'utf8')).toBe(SECRET);
});

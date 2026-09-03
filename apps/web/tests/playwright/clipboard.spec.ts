import { test, expect } from '@playwright/test';

test('clipboard text round-trips between tabs', async ({ page, context }) => {
  const a = page; await a.goto('/');
  await a.getByRole('button', { name: 'Clipboard' }).click();
  const b = await context.newPage(); await b.goto('/');
  await b.getByRole('button', { name: 'Clipboard' }).click();
  await a.locator('textarea').fill('round-trip');
  await expect(b.locator('textarea')).toHaveValue('round-trip', { timeout: 3_000 });
});

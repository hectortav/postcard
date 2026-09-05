import { test, expect } from '@playwright/test';

test('landing page renders all sections and airmail stripe', async ({ page }) => {
  // The Playwright config supplies a webServer that runs `pnpm preview`.
  await page.goto('/');

  // Airmail stripe — repeated red/navy/red gradient at the top of the page.
  // The element is 4px tall and full-width of the header; we check for its
  // presence and a non-empty computed background-image (a gradient).
  // Use toBeAttached (not toBeVisible) because the div has no children
  // and Playwright's visibility heuristic can mis-classify empty
  // presentational divs.
  const stripe = page.locator('header [aria-hidden="true"]').first();
  await expect(stripe).toBeAttached();
  const box = await stripe.boundingBox();
  expect(box?.height).toBeGreaterThan(0);
  const bg = await stripe.evaluate((el) => getComputedStyle(el).backgroundImage);
  expect(bg).toMatch(/repeating-linear-gradient/);

  // Hero headline.
  await expect(page.getByRole('heading', { level: 1 })).toContainText(/send a file across the room/);

  // Three feature cards.
  await expect(page.getByText('Local-first')).toBeVisible();
  await expect(page.getByText('Encrypted')).toBeVisible();
  await expect(page.getByText('Cross-platform')).toBeVisible();

  // Terminal block.
  await expect(page.getByText(/postcard --path/)).toBeVisible();

  // Download section: either three buttons (release exists) or
  // "Coming soon" + a GitHub releases link (no release).
  const macButton = page.getByRole('link', { name: /download macos/i });
  const comingSoon = page.getByText(/coming soon/i);
  await expect(macButton.or(comingSoon)).toBeVisible();

  // Footer github link.
  const gh = page.locator('a[href="https://github.com/hectortav/postcard"]');
  await expect(gh).toBeVisible();
});

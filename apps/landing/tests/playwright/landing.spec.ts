import { test, expect } from '@playwright/test';

test('landing page renders all its sections', async ({ page }) => {
  // The Playwright config supplies a webServer that runs `pnpm preview`.
  await page.goto('/');

  // The decorative postcard in the hero. This used to assert an "airmail stripe" with a
  // repeating-linear-gradient background, which the letterpress redesign removed: the
  // assertion had been failing ever since, unnoticed, because this suite never ran in CI.
  const decoration = page.locator('header [aria-hidden="true"]').first();
  await expect(decoration).toBeAttached();
  const box = await decoration.boundingBox();
  expect(box?.height).toBeGreaterThan(0);
  // The stamp is an inline SVG, so it proves the decoration actually rendered rather than
  // collapsing to an empty box.
  await expect(decoration.locator('svg').first()).toBeAttached();

  // Hero headline.
  await expect(page.getByRole('heading', { level: 1 })).toContainText(
    /send a file across the room/,
  );

  // Three feature cards. Scoped to their own section and matched as headings: "Cross-platform"
  // also appears twice in the comparison table below, so a bare text match resolves to three
  // elements and fails on strict mode.
  const features = page.locator('section[aria-labelledby="features-heading"]');
  for (const title of ['Local-first', 'Encrypted', 'Cross-platform']) {
    await expect(features.getByRole('heading', { level: 3, name: title })).toBeVisible();
  }

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

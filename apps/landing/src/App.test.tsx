import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/preact';
import { App } from './App';

describe('App', () => {
  const originalFetch = globalThis.fetch;
  const originalUA = Object.getOwnPropertyDescriptor(globalThis.navigator, 'userAgent');

  beforeEach(() => {
    Object.defineProperty(globalThis.navigator, 'userAgent', {
      value: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15',
      configurable: true,
    });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    if (originalUA) Object.defineProperty(globalThis.navigator, 'userAgent', originalUA);
    vi.restoreAllMocks();
  });

  it('renders all five sections: hero, features, terminal, download, footer', async () => {
    // Stub the release fetch so the Download component doesn't hit the real API.
    globalThis.fetch = vi.fn(async () => new Response('Not Found', { status: 404 })) as unknown as typeof fetch;
    render(<App />);
    // Hero
    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/send a file across the room/);
    // Features (scope to the features section because "Cross-platform" also
    // appears in the comparison table's Platform Compatibility row).
    const featuresSection = document.querySelector('section[aria-labelledby="features-heading"]') as HTMLElement;
    expect(within(featuresSection).getByText('Local-first')).toBeTruthy();
    expect(within(featuresSection).getByText('Encrypted')).toBeTruthy();
    expect(within(featuresSection).getByText('Cross-platform')).toBeTruthy();
    // Comparison table now lives between Features and Terminal.
    const comparisonSection = document.querySelector('section[aria-labelledby="comparison-heading"]') as HTMLElement;
    expect(within(comparisonSection).getByText('How it compares')).toBeTruthy();
    // Terminal
    expect(screen.getByText(/npx -y postcard/)).toBeTruthy();
    // Download (will settle into "Coming soon" given 404)
    await waitFor(() => expect(screen.getByText(/coming soon/i)).toBeTruthy());
    // Footer (anchored to the index-zr0 repo URL so it doesn't collide
    // with the "GitHub releases" link in the download section)
    const footerLink = screen.getAllByRole('link').find(
      (a) => a.getAttribute('href') === 'https://github.com/hectortav/postcard',
    );
    expect(footerLink).toBeTruthy();
    expect(footerLink?.textContent?.toLowerCase()).toBe('github');
  });
});

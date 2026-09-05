import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/preact';
import { Download } from './Download';

describe('Download', () => {
  const originalFetch = globalThis.fetch;
  const originalUA = Object.getOwnPropertyDescriptor(globalThis.navigator, 'userAgent');

  beforeEach(() => {
    Object.defineProperty(globalThis.navigator, 'userAgent', {
      value: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15',
      configurable: true,
    });
  });

  afterEach(() => {
    // Without this each test's render stays in the document, so a later getByText matches
    // elements from earlier tests.
    cleanup();
    globalThis.fetch = originalFetch;
    if (originalUA) Object.defineProperty(globalThis.navigator, 'userAgent', originalUA);
    vi.restoreAllMocks();
  });

  it('shows "Coming soon" when the release endpoint returns 404', async () => {
    globalThis.fetch = vi.fn(async () => new Response('Not Found', { status: 404 })) as unknown as typeof fetch;
    render(<Download />);
    await waitFor(() => {
      expect(screen.getByText(/coming soon/i)).toBeTruthy();
    });
    expect(screen.getByRole('link', { name: /github releases/i })).toBeTruthy();
  });

  it('shows "Coming soon" when the fetch rejects (network down)', async () => {
    globalThis.fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch;
    render(<Download />);
    await waitFor(() => {
      expect(screen.getByText(/coming soon/i)).toBeTruthy();
    });
  });

  it('renders three download buttons when a release is available', async () => {
    const body = {
      tag_name: 'v0.1.0',
      html_url: 'https://github.com/hectortav/postcard/releases/tag/v0.1.0',
      assets: [
        { name: 'postcard-0.1.0.dmg', browser_download_url: 'https://x/mac.dmg', size: 4_000_000 },
        { name: 'postcard-setup-0.1.0.exe', browser_download_url: 'https://x/win.exe', size: 3_000_000 },
        { name: 'postcard-0.1.0.AppImage', browser_download_url: 'https://x/linux.AppImage', size: 5_000_000 },
      ],
    };
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 })) as unknown as typeof fetch;
    render(<Download />);
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /download macos/i })).toBeTruthy();
    });
    expect(screen.getByRole('link', { name: /download windows/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /download linux/i })).toBeTruthy();
  });

  it('shows the loading state before the fetch resolves', () => {
    let resolve: (r: Response) => void = () => {};
    globalThis.fetch = vi.fn(
      () => new Promise<Response>((res) => { resolve = res; }),
    ) as unknown as typeof fetch;
    render(<Download />);
    expect(screen.getByText(/checking for the latest release/i)).toBeTruthy();
    resolve(new Response('Not Found', { status: 404 }));
  });
  it('shows "Coming soon" for a tagged release whose installers have not uploaded yet', async () => {
    // Between the tag landing and the installer jobs finishing, the API returns a real
    // release with an empty asset list. That is a different branch from a 404, and the page
    // must not offer three broken download buttons during that window.
    const body = {
      tag_name: 'v0.1.0',
      html_url: 'https://github.com/hectortav/postcard/releases/tag/v0.1.0',
      assets: [],
    };
    globalThis.fetch = vi.fn(
      async () => new Response(JSON.stringify(body), { status: 200 }),
    ) as unknown as typeof fetch;
    render(<Download />);
    await waitFor(() => {
      expect(screen.getByText(/coming soon/i)).toBeTruthy();
    });
  });
  it('scales the size label to the asset', () => {
    // formatSize switches unit by magnitude. Every other test uses MB-sized installers, so
    // the smaller brackets never ran -- and a "0.0 MB" label on a small build looks broken.
    const body = {
      tag_name: 'v0.1.0',
      html_url: 'https://github.com/hectortav/postcard/releases/tag/v0.1.0',
      assets: [
        { name: 'postcard-0.1.0.dmg', browser_download_url: 'https://x/mac.dmg', size: 900 },
        { name: 'postcard-setup-0.1.0.exe', browser_download_url: 'https://x/win.exe', size: 2048 },
        { name: 'postcard-0.1.0.deb', browser_download_url: 'https://x/linux.deb', size: 6_291_456 },
      ],
    };
    globalThis.fetch = vi.fn(
      async () => new Response(JSON.stringify(body), { status: 200 }),
    ) as unknown as typeof fetch;
    render(<Download />);
    return waitFor(() => {
      expect(screen.getByText(/900 B/)).toBeTruthy();
      expect(screen.getByText(/2\.0 KB/)).toBeTruthy();
      expect(screen.getByText(/6\.0 MB/)).toBeTruthy();
    });
  });
});

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, cleanup } from '@testing-library/preact';
import { App } from './App';

vi.mock('./hooks/useWebSocket', () => ({
  useWebSocket: () => ({ status: 'open', events: [], send: vi.fn() }),
}));
vi.mock('./hooks/useWakeLock', () => ({ useWakeLock: () => {} }));
vi.mock('./lib/api', () => ({
  listFiles: vi.fn().mockResolvedValue([]),
  getClipboard: vi.fn().mockResolvedValue(''),
  uploadFile: vi.fn(),
  UploadError: class extends Error {},
}));
import { listFiles } from './lib/api';

afterEach(() => {
  cleanup();
  location.hash = '';
});

describe('App', () => {
  it('renders the three tabs', () => {
    render(<App />);
    expect(screen.getByText('Files')).toBeTruthy();
    expect(screen.getByText('Clipboard')).toBeTruthy();
    expect(screen.getByText('QR')).toBeTruthy();
  });
  it('hydrates the file list exactly once', async () => {
    vi.mocked(listFiles).mockClear();
    const orig = globalThis.fetch;
    globalThis.fetch = vi.fn(async () => new Response('{}')) as unknown as typeof fetch;
    render(<App />);
    await waitFor(() => expect(vi.mocked(listFiles)).toHaveBeenCalledTimes(1));
    globalThis.fetch = orig;
  });

  it('says so when the file list cannot be loaded', async () => {
    // Silence here meant a server that had gone away looked exactly like an empty share
    // directory: the page showed "No files yet" and never updated again.
    vi.mocked(listFiles).mockRejectedValueOnce(new Error('network'));
    const orig = globalThis.fetch;
    globalThis.fetch = vi.fn(async () => new Response('{}')) as unknown as typeof fetch;
    render(<App />);
    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toMatch(/not connected|reconnecting/i),
    );
    globalThis.fetch = orig;
    vi.mocked(listFiles).mockResolvedValue([]);
  });
  it('gates the dashboard behind the PIN screen when the fragment carries one', () => {
    // `--pin` puts &pin= in the fragment. The fragment never reaches the server, so the page
    // uses it only to know that a PIN is required -- the digits still have to be verified.
    location.hash = '#key=abc&pin=1234';
    render(<App />);
    expect(screen.queryByText('Files')).toBeNull();
  });

  it('shows the dashboard directly when the fragment has no pin', () => {
    location.hash = '#key=abc';
    render(<App />);
    expect(screen.getByText('Files')).toBeTruthy();
  });

  it('switches the visible panel when a tab is selected', async () => {
    render(<App />);
    fireEvent.click(screen.getByText('QR'));
    await waitFor(() => {
      const qr = screen.getByText('QR').closest('[role=tab]');
      expect(qr?.getAttribute('aria-selected')).toBe('true');
    });
  });
});

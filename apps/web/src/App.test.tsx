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
}));

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
  it('hydrates the file list from /api/files exactly once', async () => {
    const fetches: string[] = [];
    const orig = globalThis.fetch;
    globalThis.fetch = vi.fn(async (u) => {
      fetches.push(String(u));
      return new Response('[]', { headers: { 'X-Postcard-Mode': 'lan' } });
    }) as unknown as typeof fetch;
    render(<App />);
    await waitFor(() => expect(fetches).toContain('/api/files'));
    expect(fetches.filter((u) => u === '/api/files').length).toBe(1);
    globalThis.fetch = orig;
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

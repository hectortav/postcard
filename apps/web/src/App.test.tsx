import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/preact';
import { App } from './App';

vi.mock('./hooks/useWebSocket', () => ({
  useWebSocket: () => ({ status: 'open', events: [], send: vi.fn() }),
}));
vi.mock('./hooks/useWakeLock', () => ({ useWakeLock: () => {} }));
vi.mock('./lib/api', () => ({
  listFiles: vi.fn().mockResolvedValue([]),
  getClipboard: vi.fn().mockResolvedValue(''),
}));

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
});

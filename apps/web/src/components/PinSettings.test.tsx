import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/preact';
import { PinSettings } from './PinSettings';

const origFetch = globalThis.fetch;

type Handler = (url: string, init?: RequestInit) => unknown;

function mockFetch(handler: Handler): void {
  globalThis.fetch = vi.fn(async (u: unknown, init?: RequestInit) =>
    handler(String(u), init),
  ) as unknown as typeof fetch;
}

function json(body: unknown, status = 200): unknown {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

beforeEach(() => {
  location.hash = '';
});

afterEach(() => {
  cleanup();
  globalThis.fetch = origFetch;
  location.hash = '';
});

async function renderOff(): Promise<void> {
  mockFetch((url) => {
    if (url === '/api/pin/config') return json({ pinRequired: false, manageable: true });
    throw new Error(`unexpected ${url}`);
  });
  render(<PinSettings />);
  await screen.findByText(/PIN protection/);
}

async function renderOn(): Promise<void> {
  mockFetch((url) => {
    if (url === '/api/pin/config') return json({ pinRequired: true, manageable: true });
    throw new Error(`unexpected ${url}`);
  });
  render(<PinSettings />);
  await screen.findByText(/PIN protection/);
}

describe('PinSettings', () => {
  it('shows the off state with Enable and no Disable', async () => {
    await renderOff();
    expect(screen.getByText(/anyone on your wifi can open/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Enable' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Disable' })).toBeNull();
  });

  it('shows the on state with Change and Disable', async () => {
    await renderOn();
    expect(screen.getByText(/must type the PIN/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Change' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Disable' })).toBeTruthy();
  });

  it('renders nothing for non-owners', async () => {
    mockFetch((url) => {
      if (url === '/api/pin/config') return json({ pinRequired: true, manageable: false });
      throw new Error(`unexpected ${url}`);
    });
    const { container } = render(<PinSettings />);
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(container.textContent).toBe('');
  });

  it('offers a retry when the config cannot load', async () => {
    let calls = 0;
    mockFetch((url) => {
      calls += 1;
      if (url === '/api/pin/config' && calls === 1) throw new Error('down');
      return json({ pinRequired: false, manageable: true });
    });
    render(<PinSettings />);
    await screen.findByText(/Could not reach the server/);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByRole('button', { name: 'Enable' });
    expect(calls).toBe(2);
  });

  it('keeps Enable disabled until exactly 4 digits, stripping the rest', async () => {
    await renderOff();
    const input = screen.getByLabelText('New 4-digit PIN') as HTMLInputElement;
    const enable = screen.getByRole('button', { name: 'Enable' }) as HTMLButtonElement;
    expect(enable.disabled).toBe(true);
    fireEvent.input(input, { target: { value: '12ab34' } });
    expect(input.value).toBe('1234');
    expect(enable.disabled).toBe(false);
  });

  it('enables with the typed PIN and refreshes the URL fragment', async () => {
    const seen: Array<{ url: string; body: string }> = [];
    mockFetch((url, init) => {
      if (url === '/api/pin/config') return json({ pinRequired: false, manageable: true });
      if (url === '/api/pin/configure') {
        seen.push({ url, body: String(init?.body ?? '') });
        return json({ pinRequired: true, key: 'SECRETKEY' });
      }
      throw new Error(`unexpected ${url}`);
    });
    render(<PinSettings />);
    await screen.findByText(/PIN protection/);
    fireEvent.input(screen.getByLabelText('New 4-digit PIN'), { target: { value: '5678' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enable' }));
    await waitFor(() => expect(seen.length).toBe(1));
    expect(seen[0]?.body).toBe(JSON.stringify({ pin: '5678' }));
    expect(location.hash).toContain('key=SECRETKEY');
    expect(location.hash).toContain('pin=5678');
    // Input clears after a successful change (awaits the post-submit refresh).
    const cleared = (await screen.findByLabelText('New 4-digit PIN')) as HTMLInputElement;
    expect(cleared.value).toBe('');
  });

  it('disables with an empty body and strips the PIN from the fragment', async () => {
    location.hash = '#key=SECRETKEY&pin=1234';
    const seen: string[] = [];
    mockFetch((url, init) => {
      if (url === '/api/pin/config') return json({ pinRequired: true, manageable: true });
      if (url === '/api/pin/configure') {
        seen.push(String(init?.body ?? ''));
        return json({ pinRequired: false, key: 'SECRETKEY' });
      }
      throw new Error(`unexpected ${url}`);
    });
    render(<PinSettings />);
    await screen.findByText(/PIN protection/);
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(seen.length).toBe(1));
    expect(seen[0]).toBe(JSON.stringify({}));
    expect(location.hash).toContain('key=SECRETKEY');
    expect(location.hash).not.toContain('pin=');
  });

  it('surfaces owner and validation errors without touching the fragment', async () => {
    mockFetch((url) => {
      if (url === '/api/pin/config') return json({ pinRequired: true, manageable: true });
      if (url === '/api/pin/configure') return json({ error: 'not_owner' }, 403);
      throw new Error(`unexpected ${url}`);
    });
    render(<PinSettings />);
    await screen.findByText(/PIN protection/);
    fireEvent.input(screen.getByLabelText('New 4-digit PIN'), { target: { value: '5678' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change' }));
    await screen.findByText(/only be changed from the computer running postcard/);
    expect(location.hash).toBe('');
  });
});

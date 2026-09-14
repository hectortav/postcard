import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchSession, DEFAULT_SESSION } from './session';

const original = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = original;
});

function respond(body: unknown, ok = true, status = 200) {
  globalThis.fetch = vi.fn(async () => ({
    ok,
    status,
    json: async () => body,
  })) as unknown as typeof fetch;
}

describe('fetchSession', () => {
  it('reads what the server decided about this client', async () => {
    respond({
      pinRequired: true,
      manageable: true,
      encrypted: true,
      pinLength: 6,
      mode: 'hotspot',
    });
    expect(await fetchSession()).toEqual({
      mode: 'hotspot',
      pinRequired: true,
      manageable: true,
      encrypted: true,
      pinLength: 6,
    });
  });

  it('defaults every flag closed when the server omits it', async () => {
    // A missing field must never read as "yes, you may manage the PIN".
    respond({});
    expect(await fetchSession()).toEqual(DEFAULT_SESSION);
  });

  it('coerces junk rather than trusting it', async () => {
    respond({
      pinRequired: 'yes',
      manageable: 1,
      encrypted: null,
      pinLength: -3,
      mode: 'nonsense',
    });
    const s = await fetchSession();
    expect(s.mode).toBe('lan');
    expect(s.pinRequired).toBe(false);
    expect(s.manageable).toBe(false);
    expect(s.encrypted).toBe(false);
    expect(s.pinLength).toBe(4);
  });

  it('throws on a failed response so the caller can fall back', async () => {
    respond({}, false, 500);
    await expect(fetchSession()).rejects.toThrow();
  });
});

import { b64uToBytes } from './base64url';

/**
 * What the URL fragment carries, and how to read it.
 *
 * The fragment is never sent to the server: `#key=<base64url>&pin=<digits>`. The key is the
 * session secret; with a PIN, the AES key is derived from the two together.
 *
 * The previous reader only ever asked whether a `pin` parameter was present and returned a
 * hardcoded length of 4 for it. It never looked at `key` at all, which is why nothing in the
 * browser could decrypt anything.
 */
export type Fragment = {
  /** The 32-byte session secret, or null when the URL carries none. */
  secret: Uint8Array | null;
  /** The PIN, when the host put it in the link, or null. */
  pin: string | null;
};

export function parseFragment(hash: string): Fragment {
  const raw = hash.startsWith('#') ? hash.slice(1) : hash;
  if (!raw) return { secret: null, pin: null };
  const params = new URLSearchParams(raw);

  let secret: Uint8Array | null = null;
  const key = params.get('key');
  if (key) {
    try {
      const bytes = b64uToBytes(key);
      // Anything else is not a key this server produced; treat it as absent rather than
      // failing the page, so a mangled link still shows the dashboard and a clear error.
      secret = bytes.length === 32 ? bytes : null;
    } catch {
      secret = null;
    }
  }

  const pin = params.get('pin');
  return { secret, pin: pin && /^[0-9]+$/.test(pin) ? pin : null };
}

/** Read the live location. Split out so callers can be tested without touching history. */
export function readFragment(): Fragment {
  if (typeof location === 'undefined') return { secret: null, pin: null };
  return parseFragment(location.hash);
}

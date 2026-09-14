import { describe, it, expect } from 'vitest';
import { deriveKey, saltFor, effectiveKey, encrypt, decrypt } from './pin';

// Reference vectors produced by the Java side (io.postcard.security.PinSecurityEngine)
// for secretBytes = 0x00,0x01,...,0x1f. Both ends must derive the same key from the
// same inputs, so these are the contract between the two implementations.
const SECRET_HEX = '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f';
const SALT_HEX = '630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd';
const PIN = '1234';
const KEY_FOR_1234 = '0bb7c33ae88bd1cf440a0634940923454f6962d92d0776dab9333fd1831f2830';
const KEY_FOR_9999 = '3b0579f282496c3d177025fb84b35dabf56a1a6b6de49c086e5a45bfb7fcb442';

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
// The vectors above are the Java side's own output and stay exactly as generated; only the
// call sites changed, because these functions now take the bytes rather than their hex.
const unhex = (s: string) =>
  new Uint8Array((s.match(/../g) ?? []).map((pair) => parseInt(pair, 16)));
const SECRET = unhex(SECRET_HEX);
const SALT = unhex(SALT_HEX);

// PBKDF2 at 200k iterations is deliberately slow; give these room.
const SLOW = 30_000;

describe('pin', () => {
  it('saltFor returns SHA-256(secretBytes) as hex, matching the Java side', async () => {
    expect(hex(saltFor(SECRET))).toBe(SALT_HEX);
  });

  it('saltFor is deterministic for the same secret', async () => {
    expect(hex(saltFor(SECRET))).toBe(hex(saltFor(SECRET)));
  });

  it(
    'deriveKey matches the Java reference vector',
    async () => {
      expect(hex(await deriveKey(SECRET, PIN, SALT))).toBe(KEY_FOR_1234);
    },
    SLOW,
  );

  it(
    'the PIN changes the derived key',
    async () => {
      // The whole point of --pin: knowing the URL (and therefore the secret and
      // salt) must not be enough to derive the key. If the PIN does not feed the
      // derivation, it provides no cryptographic protection at all.
      const a = await deriveKey(SECRET, '1234', SALT);
      const b = await deriveKey(SECRET, '9999', SALT);
      expect(hex(a)).not.toBe(hex(b));
      expect(hex(b)).toBe(KEY_FOR_9999);
    },
    SLOW,
  );

  it(
    'deriveKey returns 256 bits and is deterministic',
    async () => {
      const a = await deriveKey(SECRET, PIN, SALT);
      const b = await deriveKey(SECRET, PIN, SALT);
      expect(a.length).toBe(32);
      expect(hex(a)).toBe(hex(b));
    },
    SLOW,
  );

  it(
    'works without WebCrypto, because the served origin is never a secure context',
    async () => {
      // postcard serves from http://<lan-ip>:<port>, where globalThis.crypto.subtle
      // is undefined. Any reintroduction of SubtleCrypto here must fail loudly in
      // tests rather than silently in the browser.
      const realCrypto = globalThis.crypto;
      Object.defineProperty(globalThis, 'crypto', {
        value: { getRandomValues: realCrypto.getRandomValues.bind(realCrypto) },
        configurable: true,
      });
      try {
        expect(hex(saltFor(SECRET))).toBe(SALT_HEX);
        expect(hex(await deriveKey(SECRET, PIN, SALT))).toBe(KEY_FOR_1234);
      } finally {
        Object.defineProperty(globalThis, 'crypto', { value: realCrypto, configurable: true });
      }
    },
    SLOW,
  );

  it(
    'encrypt returns a 12-byte IV and decrypt round-trips the plaintext',
    async () => {
      const key = await deriveKey(SECRET, PIN, SALT);
      const pt = new TextEncoder().encode('meet me by the letterbox');
      const { iv, ciphertext } = await encrypt(key, pt);
      expect(iv.length).toBe(12);
      expect(new TextDecoder().decode(await decrypt(key, iv, ciphertext))).toBe(
        'meet me by the letterbox',
      );
    },
    SLOW,
  );

  it(
    'encrypt uses a fresh IV on every call',
    async () => {
      const key = await deriveKey(SECRET, PIN, SALT);
      const pt = new TextEncoder().encode('same plaintext');
      const a = await encrypt(key, pt);
      const b = await encrypt(key, pt);
      expect(hex(a.iv)).not.toBe(hex(b.iv));
      expect(hex(a.ciphertext)).not.toBe(hex(b.ciphertext));
    },
    SLOW,
  );

  it(
    'decrypt rejects a key derived from a different PIN',
    async () => {
      const key = await deriveKey(SECRET, '1234', SALT);
      const wrong = await deriveKey(SECRET, '9999', SALT);
      const { iv, ciphertext } = await encrypt(key, new TextEncoder().encode('secret'));
      await expect(decrypt(wrong, iv, ciphertext)).rejects.toThrow();
    },
    SLOW,
  );

  it(
    'effectiveKey derives through the PIN, and is the raw secret without one',
    async () => {
      // This is the whole point of --pin: the URL fragment alone is not enough. An attacker
      // who reads the link over someone's shoulder has the secret and therefore the salt,
      // and still has to brute-force the PIN against 200k iterations -- which the server's
      // per-IP limiter then throttles to three tries.
      expect(hex(await effectiveKey(SECRET, PIN))).toBe(KEY_FOR_1234);
      expect(hex(await effectiveKey(SECRET, '9999'))).toBe(KEY_FOR_9999);
      expect(hex(await effectiveKey(SECRET, null))).toBe(SECRET_HEX);
    },
    SLOW,
  );

  it('deriveKey rejects empty inputs', async () => {
    await expect(deriveKey(new Uint8Array(), PIN, SALT)).rejects.toThrow();
    await expect(deriveKey(SECRET, '', SALT)).rejects.toThrow();
    await expect(deriveKey(SECRET, PIN, new Uint8Array())).rejects.toThrow();
  });
});

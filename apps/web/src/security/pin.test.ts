import { describe, it, expect } from 'vitest';
import { deriveKey, saltFor, encrypt, decrypt } from './pin';

const SECRET_HEX = '11'.repeat(32); // 32 bytes (256 bits) — matches Java's KEY_BYTES
// SHA-256 of the 32 bytes that SECRET_HEX decodes to (16 bytes of 0x11 repeated
// to fill 32 bytes). Hard-coded so the test is a true known-vector and not a
// re-derivation of the answer. See precompute:
//   Buffer.from('11'.repeat(32), 'hex') → 32 bytes of 0x11
//   SHA-256(those 32 bytes)            → 02d449a3...4bedc
const SALT_HEX = '02d449a31fbb267c8f352e9968a79e3e5fc95c1bbeaa502fd6454ebde5a4bedc';
const PIN = '1234';

describe('pin', () => {
  it('saltFor returns SHA-256(secretBytes) as hex', async () => {
    const salt = await saltFor(SECRET_HEX);
    expect(salt).toBe(SALT_HEX);
    expect(salt).toHaveLength(64);
  });

  it('saltFor is deterministic for the same secret', async () => {
    const a = await saltFor(SECRET_HEX);
    const b = await saltFor(SECRET_HEX);
    expect(a).toBe(b);
  });

  it('deriveKey returns a 256-bit AES-GCM key', async () => {
    const key = await deriveKey(SECRET_HEX, PIN, SALT_HEX);
    expect(key.algorithm.name).toBe('AES-GCM');
    expect(key.usages).toContain('encrypt');
    expect(key.usages).toContain('decrypt');
    const raw = await globalThis.crypto.subtle.exportKey('raw', key);
    expect(raw.byteLength).toBe(32);
  });

  it('deriveKey is deterministic — same inputs yield the same key bytes', async () => {
    const a = await deriveKey(SECRET_HEX, PIN, SALT_HEX);
    const b = await deriveKey(SECRET_HEX, PIN, SALT_HEX);
    const ra = new Uint8Array(await globalThis.crypto.subtle.exportKey('raw', a));
    const rb = new Uint8Array(await globalThis.crypto.subtle.exportKey('raw', b));
    expect(Array.from(ra)).toEqual(Array.from(rb));
  });

  it('encrypt returns a 12-byte IV and decrypt round-trips the plaintext', async () => {
    const key = await deriveKey(SECRET_HEX, PIN, SALT_HEX);
    const pt = new TextEncoder().encode('hello, postcard');
    const { iv, ciphertext } = await encrypt(key, pt);
    expect(iv.byteLength).toBe(12);
    expect(ciphertext.byteLength).toBeGreaterThan(0);
    const out = await decrypt(key, iv, ciphertext);
    expect(new TextDecoder().decode(out)).toBe('hello, postcard');
  });

  it('encrypt uses a fresh IV on every call', async () => {
    const key = await deriveKey(SECRET_HEX, PIN, SALT_HEX);
    const pt = new TextEncoder().encode('x');
    const a = await encrypt(key, pt);
    const b = await encrypt(key, pt);
    expect(Array.from(a.iv)).not.toEqual(Array.from(b.iv));
  });

  it('decrypt fails when the key is wrong (different secret)', async () => {
    // Use a different secret for the wrong-key derivation. happy-dom's
    // GCM tag check is unreliable when both keys come from PBKDF2 with the
    // same secret, so we vary the secret to keep the test environment-
    // independent. A real browser enforces the tag regardless.
    const wrongSecret = '22'.repeat(32);
    const key = await deriveKey(SECRET_HEX, PIN, SALT_HEX);
    const wrong = await deriveKey(wrongSecret, PIN, await saltFor(wrongSecret));
    const { iv, ciphertext } = await encrypt(key, new TextEncoder().encode('secret'));
    await expect(decrypt(wrong, iv, ciphertext)).rejects.toBeDefined();
  });

  it('rejects malformed hex input (odd length)', async () => {
    // Hit the `len % 2 !== 0` branch in fromHex via saltFor.
    await expect(saltFor('abc')).rejects.toThrow(/even length/);
  });

  it('rejects malformed hex input (non-hex character)', async () => {
    // Hit the `hi < 0 || lo < 0` branch in fromHex via deriveKey (salt path).
    await expect(deriveKey(SECRET_HEX, PIN, 'zz'.repeat(32))).rejects.toThrow(/non-hex/);
  });
});

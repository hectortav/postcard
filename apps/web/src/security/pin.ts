/**
 * Browser-side PIN key derivation.
 *
 * Mirrors `io.postcard.security.PinSecurityEngine` on the Java side: both ends derive the
 * same 256-bit AES key as `PBKDF2-HMAC-SHA256(pin, salt, 200_000)`, where the salt is
 * `SHA-256(secretBytes)` in hex so both sides can compute it without any out-of-band
 * exchange. The receiver's URL fragment carries the random 256-bit secret; the user types
 * the 4-digit PIN shown on the host's terminal. Together they produce the AES key.
 *
 * Two properties are load-bearing and are pinned by tests:
 *
 * 1. **The PIN is the PBKDF2 password.** The secret only reaches the derivation through
 *    the salt. That is what makes knowing the URL insufficient to decrypt: an attacker
 *    with the fragment has the salt but must still brute-force the PIN against 200k
 *    iterations, which the server's per-IP rate limiter then throttles.
 *
 * 2. **No WebCrypto.** postcard serves from `http://<lan-ip>:<port>`, which is not a
 *    secure context, so `crypto.subtle` is `undefined` on every device that ever loads
 *    this page -- including the host. Hashing and PBKDF2 come from `@noble/hashes` and
 *    AES-GCM from `@noble/ciphers`, exactly as `lib/decrypt.ts` already does.
 *    (`crypto.getRandomValues` is *not* restricted to secure contexts and is still used
 *    for IVs.)
 *
 * Keys are raw 32-byte `Uint8Array`s rather than `CryptoKey`s, which is what the
 * streaming-decrypt path in `lib/decrypt.ts` consumes anyway.
 */
import { sha256 } from '@noble/hashes/sha2.js';
import { pbkdf2Async } from '@noble/hashes/pbkdf2.js';
import { gcm } from '@noble/ciphers/aes.js';

const PBKDF2_ITERATIONS = 200_000;
const KEY_BYTES = 32;
const IV_BYTES = 12;
const HEX = '0123456789abcdef';

function fromHex(s: string): Uint8Array {
  const len = s.length;
  if (len % 2 !== 0) throw new Error('hex string must have even length');
  const out = new Uint8Array(len / 2);
  for (let i = 0; i < len; i += 2) {
    const hi = HEX.indexOf(s.charAt(i).toLowerCase());
    const lo = HEX.indexOf(s.charAt(i + 1).toLowerCase());
    if (hi < 0 || lo < 0) throw new Error('non-hex character at ' + i);
    out[i / 2] = (hi << 4) | lo;
  }
  return out;
}

function toHex(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i] ?? 0;
    out += HEX.charAt(b >>> 4) + HEX.charAt(b & 0x0f);
  }
  return out;
}

/**
 * The deterministic salt for a secret: `SHA-256(secretBytes)` as lowercase hex.
 * Matches `PinSecurityEngine.saltFor`.
 */
export async function saltFor(secretHex: string): Promise<string> {
  return toHex(sha256(fromHex(secretHex)));
}

/**
 * Derive the AES-256-GCM key from `(secretHex, pin, saltHex)`.
 *
 * @param secretHex hex-encoded 32-byte secret from the URL fragment; reaches the
 *                  derivation only via `saltHex`, matching the Java side
 * @param pin       the PIN shown on the host's terminal -- the PBKDF2 password
 * @param saltHex   hex-encoded `SHA-256` of the secret (see {@link saltFor})
 * @returns the raw 32-byte key
 */
export async function deriveKey(
  secretHex: string,
  pin: string,
  saltHex: string,
): Promise<Uint8Array> {
  if (!secretHex) throw new Error('secret must not be empty');
  if (!pin) throw new Error('pin must not be empty');
  // Parsed for validation parity with the Java side, which rejects a malformed secret
  // before deriving. The bytes themselves enter the derivation through the salt.
  fromHex(secretHex);
  return pbkdf2Async(sha256, new TextEncoder().encode(pin), fromHex(saltHex), {
    c: PBKDF2_ITERATIONS,
    dkLen: KEY_BYTES,
  });
}

/** AES-GCM encrypt with a fresh 12-byte IV. Returns the IV and ciphertext. */
export async function encrypt(
  key: Uint8Array,
  plaintext: Uint8Array,
): Promise<{ iv: Uint8Array; ciphertext: Uint8Array }> {
  const iv = new Uint8Array(IV_BYTES);
  // Not gated on a secure context, unlike crypto.subtle.
  globalThis.crypto.getRandomValues(iv);
  return { iv, ciphertext: gcm(key, iv).encrypt(plaintext) };
}

/** AES-GCM decrypt. Throws if the key/IV/ciphertext don't match (GCM auth tag). */
export async function decrypt(
  key: Uint8Array,
  iv: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  return gcm(key, iv).decrypt(ciphertext);
}

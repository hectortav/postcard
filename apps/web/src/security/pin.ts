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
 * Keys, secrets and salts are all raw `Uint8Array`s here. They used to be hex strings while
 * the server emitted base64url and `lib/decrypt.ts` expected standard base64 -- three
 * encodings for one value, none of which agreed. Bytes are what PBKDF2 and AES-GCM actually
 * consume, so the conversion happens once, at the edge, where the URL fragment is parsed.
 */
import { sha256 } from '@noble/hashes/sha2.js';
import { pbkdf2Async } from '@noble/hashes/pbkdf2.js';
import { gcm } from '@noble/ciphers/aes.js';

const PBKDF2_ITERATIONS = 200_000;
const KEY_BYTES = 32;
const IV_BYTES = 12;
/**
 * The deterministic salt for a secret: `SHA-256(secret)`.
 *
 * Matches `PinSecurityEngine.saltFor`, which returns the same digest hex-encoded and then
 * decodes it again before use -- the bytes are the contract, the hex was only a transport.
 */
export function saltFor(secret: Uint8Array): Uint8Array {
  return sha256(secret);
}

/**
 * Derive the AES-256-GCM key from `(secret, pin, salt)`.
 *
 * @param secret the 32-byte secret from the URL fragment; reaches the derivation only via
 *               `salt`, matching the Java side
 * @param pin    the PIN shown on the host's terminal -- the PBKDF2 password
 * @param salt   `SHA-256` of the secret (see {@link saltFor})
 * @returns the raw 32-byte key
 */
export async function deriveKey(
  secret: Uint8Array,
  pin: string,
  salt: Uint8Array,
): Promise<Uint8Array> {
  if (!secret || secret.length === 0) throw new Error('secret must not be empty');
  if (!pin) throw new Error('pin must not be empty');
  if (!salt || salt.length === 0) throw new Error('salt must not be empty');
  return pbkdf2Async(sha256, new TextEncoder().encode(pin), salt, {
    c: PBKDF2_ITERATIONS,
    dkLen: KEY_BYTES,
  });
}

/**
 * The key for a session: the raw secret when there is no PIN, and the PBKDF2 derivation of
 * (PIN, SHA-256(secret)) when there is one. Mirrors what the server encrypts with, so the two
 * sides agree without ever exchanging the derived key.
 */
export async function effectiveKey(secret: Uint8Array, pin: string | null): Promise<Uint8Array> {
  if (!pin) return secret;
  return deriveKey(secret, pin, saltFor(secret));
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

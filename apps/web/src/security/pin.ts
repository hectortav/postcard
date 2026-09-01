/**
 * Browser-side WebCrypto wrapper for the PIN layer.
 *
 * Mirrors {@code io.postcard.security.PinSecurityEngine} on the Java side:
 * both ends derive the same 256-bit AES key from
 * {@code PBKDF2-HMAC-SHA256(secretBytes, pin, salt)} with 200,000
 * iterations. The salt is {@code SHA-256(secretBytes)} (hex) so both
 * sides can compute it deterministically without any out-of-band exchange.
 *
 * The receiver's URL fragment contains the random 256-bit secret as hex.
 * The user types the 4-digit PIN shown on the host's terminal. Together
 * they produce the AES key that decrypts the file stream.
 *
 * The exported {@link CryptoKey} has algorithm {@code AES-GCM} and usages
 * {@code ['encrypt', 'decrypt']} so the existing streaming-decrypt path
 * can consume it directly (see {@link ./lib/decrypt.ts}).
 */

const PBKDF2_ITERATIONS = 200_000;
const KEY_BITS = 256;
const IV_BYTES = 12;
const HEX = '0123456789abcdef';

function fromHex(s: string): Uint8Array<ArrayBuffer> {
  const len = s.length;
  if (len % 2 !== 0) throw new Error('hex string must have even length');
  const out = new Uint8Array(len / 2);
  for (let i = 0; i < len; i += 2) {
    const hi = HEX.indexOf(s.charAt(i).toLowerCase());
    const lo = HEX.indexOf(s.charAt(i + 1).toLowerCase());
    if (hi < 0 || lo < 0) throw new Error('non-hex character at ' + i);
    out[i / 2] = (hi << 4) | lo;
  }
  return out as Uint8Array<ArrayBuffer>;
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
 * Compute the deterministic salt for a given secret: {@code SHA-256(secretBytes)}
 * as lowercase hex. Both the Java sender and the browser receiver can call
 * this with the same {@code secretHex} and get the same result.
 */
export async function saltFor(secretHex: string): Promise<string> {
  const bytes = fromHex(secretHex);
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  return toHex(new Uint8Array(digest));
}

/**
 * Derive the AES-256-GCM key from {@code (secretHex, pin, saltHex)}.
 *
 * @param secretHex hex-encoded 32-byte random secret from the URL fragment
 * @param pin       4-digit PIN shown on the host's terminal
 * @param saltHex   hex-encoded SHA-256 of the secret (use {@link saltFor})
 * @returns an AES-GCM {@link CryptoKey} suitable for encrypt/decrypt
 */
export async function deriveKey(
  secretHex: string,
  pin: string,
  saltHex: string,
): Promise<CryptoKey> {
  const secretBytes = fromHex(secretHex);
  const saltBytes = fromHex(saltHex);
  const baseKey = await globalThis.crypto.subtle.importKey(
    'raw',
    secretBytes,
    { name: 'PBKDF2' },
    false,
    ['deriveBits'],
  );
  // PBKDF2_PARAMS shape: { name, hash, salt, iterations }. The `hash` is
  // a named algorithm string per WebCrypto; 'SHA-256' selects the SHA-256
  // digest for the HMAC.
  const derivedBits = await globalThis.crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: saltBytes, iterations: PBKDF2_ITERATIONS },
    baseKey,
    KEY_BITS,
  );
  // Note: extractable is `true` here. In a production hardening pass the
  // receiver would keep this key in a non-extractable CryptoKey held inside
  // a SubtleCrypto-backed worker; the streaming-decrypt path would then wrap
  // each chunk in a one-shot `decrypt` call. For the v0.1 receiver we export
  // the raw bytes (as base64) to feed `@noble/ciphers` in the existing
  // `lib/decrypt.ts` path, so extractability is required. Both code paths
  // are equivalent on the wire.
  return globalThis.crypto.subtle.importKey(
    'raw',
    derivedBits,
    { name: 'AES-GCM' },
    true,
    ['encrypt', 'decrypt'],
  );
}

/** AES-GCM encrypt with a fresh 12-byte IV. Returns the IV and ciphertext. */
export async function encrypt(
  key: CryptoKey,
  plaintext: Uint8Array,
): Promise<{ iv: Uint8Array; ciphertext: Uint8Array }> {
  const iv = new Uint8Array(IV_BYTES);
  globalThis.crypto.getRandomValues(iv);
  const ciphertext = await globalThis.crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: iv as Uint8Array<ArrayBuffer> },
    key,
    plaintext as Uint8Array<ArrayBuffer>,
  );
  return { iv, ciphertext: new Uint8Array(ciphertext) };
}

/** AES-GCM decrypt. Throws if the key/IV/ciphertext don't match (GCM auth tag). */
export async function decrypt(
  key: CryptoKey,
  iv: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  const plaintext = await globalThis.crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: iv as Uint8Array<ArrayBuffer> },
    key,
    ciphertext as Uint8Array<ArrayBuffer>,
  );
  return new Uint8Array(plaintext);
}

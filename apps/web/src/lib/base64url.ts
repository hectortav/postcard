/**
 * base64url, without padding — the one encoding postcard uses for key material.
 *
 * There were three. The server emits base64url (`Base64.getUrlEncoder().withoutPadding()`),
 * `lib/decrypt.ts` decoded with `atob`, which is standard base64 and throws outright on the
 * `-` and `_` that base64url produces, and `security/pin.ts` expected hex. No two of them
 * could have interoperated, which is part of why no browser ever decrypted anything.
 *
 * `atob`/`btoa` are used underneath (they are not restricted to secure contexts) after
 * translating the alphabet.
 */

/** Decode base64url, with or without padding. Throws on characters outside the alphabet. */
export function b64uToBytes(s: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*={0,2}$/.test(s)) throw new Error('not base64url');
  const standard = s.replace(/-/g, '+').replace(/_/g, '/');
  const padded = standard + '='.repeat((4 - (standard.length % 4)) % 4);
  const binary = atob(padded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/** Encode as base64url with no padding, matching the server. */
export function bytesToB64u(bytes: Uint8Array): string {
  let binary = '';
  // Chunked so a large input cannot blow the argument limit of String.fromCharCode.
  const STEP = 0x8000;
  for (let i = 0; i < bytes.length; i += STEP) {
    binary += String.fromCharCode(...bytes.subarray(i, i + STEP));
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

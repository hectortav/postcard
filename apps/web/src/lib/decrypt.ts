import { gcm } from '@noble/ciphers/aes';

export function decryptChunk(keyB64: string, nonce: Uint8Array, ct: Uint8Array): Uint8Array {
  const keyBytes = Uint8Array.from(atob(keyB64), (c) => c.charCodeAt(0));
  return gcm(keyBytes, nonce).decrypt(ct);
}

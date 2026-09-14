import { gcm } from '@noble/ciphers/aes.js';

/** Plaintext bytes per chunk. Pinned to `ChunkCipher.CHUNK_BYTES` on the Java side. */
export const CHUNK_BYTES = 64 * 1024;
/** Per-chunk nonce length. */
export const NONCE_BYTES = 12;
/** GCM tag length. */
export const TAG_BYTES = 16;
/** A full chunk as it appears on the wire: nonce, ciphertext, tag. */
export const WIRE_CHUNK_BYTES = NONCE_BYTES + CHUNK_BYTES + TAG_BYTES;

/**
 * Associated data for one chunk, byte-for-byte identical to `ChunkCipher.aad` in Java:
 * the chunk index as a big-endian 64-bit integer, a final-chunk flag, then the file id.
 *
 * Chunks are individually authenticated, so without this an attacker on the network could
 * reorder them, drop the tail, or splice in chunks from another file in the same session and
 * every tag would still verify.
 */
export function chunkAad(index: number, last: boolean, fileId: string): Uint8Array {
  const id = new TextEncoder().encode(fileId);
  const out = new Uint8Array(9 + id.length);
  const view = new DataView(out.buffer);
  view.setBigUint64(0, BigInt(index), false);
  out[8] = last ? 1 : 0;
  out.set(id, 9);
  return out;
}

/**
 * Decrypt one chunk. Throws if the tag does not verify — which includes the case where the
 * chunk is genuine but is not the chunk that belongs at this position in this file.
 */
export function decryptChunk(
  key: Uint8Array,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
  index: number,
  last: boolean,
  fileId: string,
): Uint8Array {
  return gcm(key, nonce, chunkAad(index, last, fileId)).decrypt(ciphertext);
}

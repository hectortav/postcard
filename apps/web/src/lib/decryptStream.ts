import { CHUNK_BYTES, NONCE_BYTES, TAG_BYTES, WIRE_CHUNK_BYTES, decryptChunk } from './decrypt';

/**
 * Turn an encrypted download into a Blob the browser can save.
 *
 * ## Why it is buffered rather than streamed to disk
 *
 * postcard serves from `http://<lan-ip>:<port>`, which browsers do not treat as a secure
 * context. That rules out both ways of streaming a generated file straight to disk: Service
 * Workers (the StreamSaver approach) and the File System Access API are each gated on a
 * secure context, and the latter does not exist on iOS Safari in any case. What is left is to
 * decrypt in memory and hand the browser a Blob.
 *
 * Decrypted output is flushed into Blob slabs as it goes, so the JavaScript heap only ever
 * holds one slab: the browser owns the rest and can spill it to disk. That makes large files
 * workable on desktop, but it is not unlimited, and a phone will give up long before a laptop
 * does. Callers should warn above {@link WARN_ABOVE_BYTES}.
 *
 * The host's own dashboard never comes through here — the server sends it plaintext, because
 * it already has the file on disk and its own traffic never reaches the network.
 */

/** Decrypted bytes to accumulate before handing a slab to the browser. */
const SLAB_BYTES = 8 * 1024 * 1024;

/**
 * Size above which a caller should confirm before starting. Not a hard limit: it is the point
 * where a phone browser starts to be a real risk of failing partway.
 */
export const WARN_ABOVE_BYTES = 512 * 1024 * 1024;

export class DecryptionError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'DecryptionError';
  }
}

/** Concatenate the head of a queue of buffers into exactly `n` bytes, consuming them. */
function take(buffers: Uint8Array[], n: number): Uint8Array {
  const out = new Uint8Array(n);
  let filled = 0;
  while (filled < n) {
    const head = buffers[0];
    if (head === undefined) throw new DecryptionError('stream ended mid-chunk');
    const want = n - filled;
    if (head.length <= want) {
      out.set(head, filled);
      filled += head.length;
      buffers.shift();
    } else {
      out.set(head.subarray(0, want), filled);
      buffers[0] = head.subarray(want);
      filled = n;
    }
  }
  return out;
}

function buffered(buffers: Uint8Array[]): number {
  let total = 0;
  for (const b of buffers) total += b.length;
  return total;
}

/**
 * Read `body`, decrypt it chunk by chunk, and resolve with the plaintext as a Blob.
 *
 * Rejects — rather than resolving with partial content — if any tag fails to verify or the
 * stream ends mid-chunk. A caller must never be handed bytes that did not authenticate.
 *
 * @param onProgress receives plaintext bytes produced so far
 */
export async function decryptToBlob(
  key: Uint8Array,
  body: ReadableStream<Uint8Array>,
  fileId: string,
  onProgress?: (plaintextBytes: number) => void,
  signal?: AbortSignal,
): Promise<Blob> {
  const reader = body.getReader();
  const pending: Uint8Array[] = [];
  const slabs: Blob[] = [];
  let slab: Uint8Array[] = [];
  let slabBytes = 0;
  let index = 0;
  let produced = 0;

  const flushSlab = () => {
    if (slab.length === 0) return;
    slabs.push(new Blob(slab as BlobPart[]));
    slab = [];
    slabBytes = 0;
  };

  const emit = (plain: Uint8Array) => {
    slab.push(plain);
    slabBytes += plain.length;
    produced += plain.length;
    onProgress?.(produced);
    if (slabBytes >= SLAB_BYTES) flushSlab();
  };

  const decryptOne = (wire: Uint8Array, last: boolean) => {
    const nonce = wire.subarray(0, NONCE_BYTES);
    const ciphertext = wire.subarray(NONCE_BYTES);
    try {
      emit(decryptChunk(key, nonce, ciphertext, index, last, fileId));
    } catch (cause) {
      throw new DecryptionError(
        last
          ? 'The end of this file did not verify. It may be incomplete or altered.'
          : 'Part of this file did not verify. It may have been altered in transit.',
        { cause },
      );
    }
    index += 1;
  };

  try {
    for (;;) {
      if (signal?.aborted) throw new DecryptionError('cancelled');
      const { done, value } = await reader.read();
      if (value !== undefined && value.length > 0) pending.push(value);
      if (done) break;
      // Only a chunk with bytes strictly after it is certain not to be the last one, so a
      // full chunk sitting at the end of the buffer has to wait for the next read.
      while (buffered(pending) > WIRE_CHUNK_BYTES) {
        decryptOne(take(pending, WIRE_CHUNK_BYTES), false);
      }
    }

    const remaining = buffered(pending);
    if (remaining === 0) {
      if (index === 0) return new Blob([]); // an empty file encrypts to an empty stream
      throw new DecryptionError('stream ended mid-chunk');
    }
    if (remaining < NONCE_BYTES + TAG_BYTES || remaining > WIRE_CHUNK_BYTES) {
      throw new DecryptionError('stream ended mid-chunk');
    }
    decryptOne(take(pending, remaining), true);
  } finally {
    try { await reader.cancel(); } catch { /* already closed */ }
  }

  flushSlab();
  return new Blob(slabs as BlobPart[]);
}

/** Plaintext size for a given ciphertext length, for progress reporting. */
export function plaintextLength(ciphertextBytes: number): number {
  if (ciphertextBytes <= 0) return 0;
  const overhead = NONCE_BYTES + TAG_BYTES;
  const full = Math.floor(ciphertextBytes / WIRE_CHUNK_BYTES);
  const rest = ciphertextBytes % WIRE_CHUNK_BYTES;
  return full * CHUNK_BYTES + (rest === 0 ? 0 : Math.max(0, rest - overhead));
}

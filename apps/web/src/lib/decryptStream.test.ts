import { describe, it, expect } from 'vitest';
import { gcm } from '@noble/ciphers/aes.js';
import { decryptToBlob, plaintextLength, DecryptionError } from './decryptStream';
import { chunkAad, CHUNK_BYTES, NONCE_BYTES, WIRE_CHUNK_BYTES } from './decrypt';
import { b64uToBytes } from './base64url';

// Produced by io.postcard.crypto.ChunkCipher.encryptAll(pt, km, "test-file-id") with a key of
// 0x00..0x1f. A stream the real server would emit, decrypted by the real browser code.
const FILE_ID = 'test-file-id';
const KEY = b64uToBytes('AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8');
const JAVA_STREAM = b64uToBytes(
  '2VW-2DtPV62KC_1bz0LwRdlHD4FXU9FVd1vTH3YIbvUApRbddQ2UZPnhzD1Hw7uwbeubhR-PPhzBRUThaoOwlVksfCozF8A',
);
const JAVA_PLAINTEXT = 'the quick brown fox jumps over the lazy dog';

/** A ReadableStream that hands out `chunks` in order, to model arbitrary network framing. */
function streamOf(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  let i = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i < chunks.length) controller.enqueue(chunks[i++]!);
      else controller.close();
    },
  });
}

/** Encrypt `plaintext` exactly as the server does, for multi-chunk cases. */
function encryptLikeServer(plaintext: Uint8Array, fileId = FILE_ID): Uint8Array {
  if (plaintext.length === 0) return new Uint8Array();
  const pieces: Uint8Array[] = [];
  const total = Math.ceil(plaintext.length / CHUNK_BYTES);
  for (let index = 0; index < total; index++) {
    const part = plaintext.subarray(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES);
    const nonce = new Uint8Array(NONCE_BYTES).fill(index + 1);
    const ct = gcm(KEY, nonce, chunkAad(index, index === total - 1, fileId)).encrypt(part);
    const wire = new Uint8Array(nonce.length + ct.length);
    wire.set(nonce, 0);
    wire.set(ct, nonce.length);
    pieces.push(wire);
  }
  const out = new Uint8Array(pieces.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of pieces) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

const bytesOf = async (b: Blob) => new Uint8Array(await b.arrayBuffer());

describe('decryptToBlob', () => {
  it('decrypts a stream produced by the Java server', async () => {
    const blob = await decryptToBlob(KEY, streamOf([JAVA_STREAM]), FILE_ID);
    expect(new TextDecoder().decode(await bytesOf(blob))).toBe(JAVA_PLAINTEXT);
  });

  it('decrypts across several full chunks and a short tail', async () => {
    const plaintext = new Uint8Array(CHUNK_BYTES * 2 + 1234);
    for (let i = 0; i < plaintext.length; i++) plaintext[i] = (i * 31) % 256;
    const blob = await decryptToBlob(KEY, streamOf([encryptLikeServer(plaintext)]), FILE_ID);
    expect(Array.from(await bytesOf(blob))).toEqual(Array.from(plaintext));
  });

  it('is indifferent to how the network frames the bytes', async () => {
    // Chunk boundaries almost never line up with network reads, so the parser has to
    // reassemble across them -- including a read that ends exactly on a boundary.
    const plaintext = new Uint8Array(CHUNK_BYTES + 500).fill(9);
    const wire = encryptLikeServer(plaintext);
    const framings: Uint8Array[][] = [
      [wire],
      [wire.subarray(0, 1), wire.subarray(1)],
      [wire.subarray(0, WIRE_CHUNK_BYTES), wire.subarray(WIRE_CHUNK_BYTES)],
      [wire.subarray(0, 7), wire.subarray(7, 60000), wire.subarray(60000)],
    ];
    for (const framing of framings) {
      const blob = await decryptToBlob(KEY, streamOf(framing), FILE_ID);
      expect((await bytesOf(blob)).length).toBe(plaintext.length);
    }
  });

  it('reports progress in plaintext bytes', async () => {
    const plaintext = new Uint8Array(CHUNK_BYTES * 2).fill(3);
    const seen: number[] = [];
    await decryptToBlob(KEY, streamOf([encryptLikeServer(plaintext)]), FILE_ID, (n) =>
      seen.push(n),
    );
    expect(seen.at(-1)).toBe(plaintext.length);
    // Progress only ever moves forward.
    for (let i = 1; i < seen.length; i++) expect(seen[i]!).toBeGreaterThan(seen[i - 1]!);
  });

  it('handles an empty file', async () => {
    const blob = await decryptToBlob(KEY, streamOf([]), FILE_ID);
    expect((await bytesOf(blob)).length).toBe(0);
  });

  it('rejects a tampered chunk instead of returning partial bytes', async () => {
    const wire = encryptLikeServer(new Uint8Array(CHUNK_BYTES + 10).fill(1));
    const tampered = new Uint8Array(wire);
    tampered[20] = (tampered[20] ?? 0) ^ 0xff;
    await expect(decryptToBlob(KEY, streamOf([tampered]), FILE_ID)).rejects.toThrow(
      DecryptionError,
    );
  });

  it('rejects a truncated stream', async () => {
    // Every surviving chunk is intact and individually valid; what gives it away is that the
    // chunk now at the end does not claim to be the end.
    const wire = encryptLikeServer(new Uint8Array(CHUNK_BYTES * 2 + 40).fill(1));
    const truncated = wire.subarray(0, WIRE_CHUNK_BYTES * 2);
    await expect(decryptToBlob(KEY, streamOf([truncated]), FILE_ID)).rejects.toThrow(
      DecryptionError,
    );
  });

  it('rejects a stream that ends mid-chunk', async () => {
    const wire = encryptLikeServer(new Uint8Array(100).fill(1));
    await expect(decryptToBlob(KEY, streamOf([wire.subarray(0, 8)]), FILE_ID)).rejects.toThrow(
      DecryptionError,
    );
  });

  it('rejects the right key against the wrong file', async () => {
    const wire = encryptLikeServer(new Uint8Array(100).fill(1), 'file-one');
    await expect(decryptToBlob(KEY, streamOf([wire]), 'file-two')).rejects.toThrow(DecryptionError);
  });

  it('rejects the wrong key', async () => {
    const wire = encryptLikeServer(new Uint8Array(100).fill(1));
    const wrong = new Uint8Array(32).fill(0xaa);
    await expect(decryptToBlob(wrong, streamOf([wire]), FILE_ID)).rejects.toThrow(DecryptionError);
  });
});

describe('plaintextLength', () => {
  it('inverts the ciphertext overhead', () => {
    expect(plaintextLength(0)).toBe(0);
    expect(plaintextLength(NONCE_BYTES + 42 + 16)).toBe(42);
    expect(plaintextLength(WIRE_CHUNK_BYTES)).toBe(CHUNK_BYTES);
    expect(plaintextLength(WIRE_CHUNK_BYTES + NONCE_BYTES + 10 + 16)).toBe(CHUNK_BYTES + 10);
  });
});

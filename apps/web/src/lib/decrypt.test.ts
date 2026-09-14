import { describe, it, expect } from 'vitest';
import { gcm } from '@noble/ciphers/aes.js';
import { chunkAad, decryptChunk, CHUNK_BYTES, NONCE_BYTES, TAG_BYTES } from './decrypt';
import { b64uToBytes, bytesToB64u } from './base64url';

// Reference vectors produced by io.postcard.crypto.ChunkCipher. These are the contract
// between the two implementations: the browser has to reconstruct the associated data
// byte-for-byte or every chunk the server sends fails its tag check.
const FILE_ID = 'test-file-id';
const AAD_0_LAST = 'AAAAAAAAAAABdGVzdC1maWxlLWlk';
const AAD_1_NOTLAST = 'AAAAAAAAAAEAdGVzdC1maWxlLWlk';
const AAD_2_EMPTYID = 'AAAAAAAAAAIA';
const KEY_B64URL = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8';

describe('chunkAad', () => {
  it('matches the Java associated-data layout', () => {
    expect(bytesToB64u(chunkAad(0, true, FILE_ID))).toBe(AAD_0_LAST);
    expect(bytesToB64u(chunkAad(1, false, FILE_ID))).toBe(AAD_1_NOTLAST);
    expect(bytesToB64u(chunkAad(2, false, ''))).toBe(AAD_2_EMPTYID);
  });

  it('distinguishes position, finality and file', () => {
    const a = bytesToB64u(chunkAad(0, true, FILE_ID));
    expect(a).not.toBe(bytesToB64u(chunkAad(1, true, FILE_ID)));
    expect(a).not.toBe(bytesToB64u(chunkAad(0, false, FILE_ID)));
    expect(a).not.toBe(bytesToB64u(chunkAad(0, true, 'another-file')));
  });
});

describe('decryptChunk', () => {
  const key = b64uToBytes(KEY_B64URL);
  const nonce = new Uint8Array(NONCE_BYTES).fill(0x07);
  const plaintext = new TextEncoder().encode('hello, postcard');
  const ciphertext = gcm(key, nonce, chunkAad(0, true, FILE_ID)).encrypt(plaintext);

  it('decrypts a chunk in its own position', () => {
    const out = decryptChunk(key, nonce, ciphertext, 0, true, FILE_ID);
    expect(new TextDecoder().decode(out)).toBe('hello, postcard');
  });

  it('throws when the tag does not match', () => {
    const tampered = new Uint8Array(ciphertext);
    tampered[0] = (tampered[0] ?? 0) ^ 0x01;
    expect(() => decryptChunk(key, nonce, tampered, 0, true, FILE_ID)).toThrow();
  });

  it('refuses a genuine chunk offered at the wrong position', () => {
    expect(() => decryptChunk(key, nonce, ciphertext, 1, true, FILE_ID)).toThrow();
    expect(() => decryptChunk(key, nonce, ciphertext, 0, false, FILE_ID)).toThrow();
    expect(() => decryptChunk(key, nonce, ciphertext, 0, true, 'another-file')).toThrow();
  });
});

describe('wire constants', () => {
  it('match the Java side', () => {
    expect(CHUNK_BYTES).toBe(64 * 1024);
    expect(NONCE_BYTES).toBe(12);
    expect(TAG_BYTES).toBe(16);
  });
});

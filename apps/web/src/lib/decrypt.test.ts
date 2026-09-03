import { describe, it, expect } from 'vitest';
import { decryptChunk } from './decrypt';
import { gcm } from '@noble/ciphers/aes.js';

const KEY = new Uint8Array(32).fill(0x42);
const KEY_B64 = btoa(String.fromCharCode(...KEY));
const NONCE = new Uint8Array(12).fill(0x07);
const PT = new TextEncoder().encode('hello, postcard');
const CT = gcm(KEY, NONCE).encrypt(PT);

describe('decryptChunk', () => {
  it('decrypts a known ciphertext', () => {
    const out = decryptChunk(KEY_B64, NONCE, CT);
    expect(new TextDecoder().decode(out)).toBe('hello, postcard');
  });
  it('throws when the tag does not match', () => {
    const tampered = new Uint8Array(CT); tampered[0] = (tampered[0] ?? 0) ^ 0x01;
    expect(() => decryptChunk(KEY_B64, NONCE, tampered)).toThrow();
  });
});

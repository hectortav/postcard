import { describe, it, expect } from 'vitest';
import { b64uToBytes, bytesToB64u } from './base64url';

describe('base64url', () => {
  it('decodes the alphabet the server emits, including - and _', () => {
    // The old decoder used atob, which throws on exactly these two characters -- so any key
    // containing one could never have been read by the browser.
    const bytes = b64uToBytes('-_-_');
    expect(Array.from(bytes)).toEqual([251, 255, 191]);
  });

  it('round-trips arbitrary bytes', () => {
    for (const len of [0, 1, 2, 3, 31, 32, 64, 1000]) {
      const bytes = new Uint8Array(len);
      for (let i = 0; i < len; i++) bytes[i] = (i * 37) % 256;
      expect(Array.from(b64uToBytes(bytesToB64u(bytes)))).toEqual(Array.from(bytes));
    }
  });

  it('emits no padding, matching Base64.getUrlEncoder().withoutPadding()', () => {
    expect(bytesToB64u(new Uint8Array([1]))).not.toContain('=');
    expect(bytesToB64u(new Uint8Array([1, 2]))).not.toContain('=');
  });

  it('accepts padded input anyway', () => {
    expect(Array.from(b64uToBytes('AQID'))).toEqual([1, 2, 3]);
    expect(Array.from(b64uToBytes('AQI='))).toEqual([1, 2]);
  });

  it('decodes a real 256-bit key to 32 bytes', () => {
    expect(b64uToBytes('AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8').length).toBe(32);
  });

  it('rejects input outside the alphabet', () => {
    expect(() => b64uToBytes('not base64!')).toThrow();
    expect(() => b64uToBytes('++//')).toThrow();
  });
});

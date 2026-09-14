import { describe, it, expect } from 'vitest';
import { parseFragment } from './fragment';
import { bytesToB64u } from './base64url';

const KEY = new Uint8Array(32).map((_, i) => i);
const KEY_B64U = bytesToB64u(KEY);

describe('parseFragment', () => {
  it('reads the key the server puts in the link', () => {
    // The old reader never looked at `key` at all -- it only asked whether a `pin` parameter
    // existed and returned a hardcoded length of 4. That is why nothing ever decrypted.
    const f = parseFragment(`#key=${KEY_B64U}`);
    expect(f.secret).not.toBeNull();
    expect(Array.from(f.secret!)).toEqual(Array.from(KEY));
    expect(f.pin).toBeNull();
  });

  it('reads key and pin together, in the order the CLI writes them', () => {
    const f = parseFragment(`#key=${KEY_B64U}&pin=1234`);
    expect(f.secret).not.toBeNull();
    expect(f.pin).toBe('1234');
  });

  it('handles a pin on its own', () => {
    const f = parseFragment('#pin=4321');
    expect(f.secret).toBeNull();
    expect(f.pin).toBe('4321');
  });

  it('is empty for a bare URL', () => {
    expect(parseFragment('')).toEqual({ secret: null, pin: null });
    expect(parseFragment('#')).toEqual({ secret: null, pin: null });
  });

  it('treats a mangled key as absent rather than throwing', () => {
    // A truncated or corrupted link should still render a dashboard that can explain itself.
    expect(parseFragment('#key=not-valid-base64!!').secret).toBeNull();
    expect(parseFragment('#key=AQID').secret).toBeNull(); // right alphabet, wrong length
  });

  it('ignores a non-numeric pin', () => {
    expect(parseFragment('#pin=abcd').pin).toBeNull();
  });

  it('keeps working when other parameters are present', () => {
    const f = parseFragment(`#tab=qr&key=${KEY_B64U}&pin=1234&x=1`);
    expect(f.secret).not.toBeNull();
    expect(f.pin).toBe('1234');
  });
});

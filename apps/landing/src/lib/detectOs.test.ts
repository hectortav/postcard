import { describe, it, expect } from 'vitest';
import { detectOs } from './detectOs';

describe('detectOs', () => {
  it('returns "mac" for macOS user agents', () => {
    expect(
      detectOs('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15'),
    ).toBe('mac');
  });

  it('returns "win" for Windows user agents', () => {
    expect(
      detectOs('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'),
    ).toBe('win');
  });

  it('returns "linux" for Linux user agents', () => {
    expect(detectOs('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36')).toBe('linux');
  });

  it('returns "linux" for unknown / missing user agents', () => {
    expect(detectOs(undefined)).toBe('linux');
    expect(detectOs(null)).toBe('linux');
    expect(detectOs('')).toBe('linux');
    expect(detectOs('SomeRandomString/1.0')).toBe('linux');
  });

  it('matches Mac first when a UA contains both "Mac" and "Linux" (defensive)', () => {
    // Some bots / crawlers self-identify oddly; "Mac" wins because it
    // appears first in the precedence chain.
    expect(detectOs('Mozilla/5.0 (Macintosh; Linux x86_64)')).toBe('mac');
  });
});

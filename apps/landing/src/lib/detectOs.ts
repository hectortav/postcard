/**
 * OS detection from the user agent string.
 *
 * Intentionally lightweight: we only need to know which of the three
 * installer buttons to highlight. Match order is fixed: Mac first
 * (because iOS/iPadOS also contain "Mac" in their UA on iPad Safari
 * with "Request Desktop Website" disabled — see AppleWebKit on iOS),
 * then Windows, then Linux. Anything else falls back to Linux because
 * the AppImage / .deb tarball is the most universal install path.
 */

export type OsId = 'mac' | 'win' | 'linux';

const MAC_RE = /Mac/i;
const WIN_RE = /Windows/i;
const LIN_RE = /Linux/i;

export function detectOs(ua: string | undefined | null): OsId {
  if (!ua) return 'linux';
  if (MAC_RE.test(ua)) return 'mac';
  if (WIN_RE.test(ua)) return 'win';
  if (LIN_RE.test(ua)) return 'linux';
  return 'linux';
}

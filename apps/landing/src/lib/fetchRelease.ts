/**
 * GitHub Releases fetch for the sendme installer artifacts.
 *
 * The landing page calls the unauthenticated REST endpoint to discover
 * the most recent release's asset URLs and sizes, then hands them to the
 * Download component. Errors and 404s (no release published yet) are
 * expected states — callers handle them by falling back to a
 * "Coming soon" message rather than a toast / crash.
 */

import type { OsId } from './detectOs';

export const RELEASES_URL = 'https://api.github.com/repos/index-zr0/sendme/releases/latest';

export type ReleaseAsset = {
  os: OsId;
  name: string;
  url: string;
  size: number;
};

export type Release = {
  tag: string;
  htmlUrl: string;
  assets: ReleaseAsset[];
};

const GITHUB_API_HEADERS: HeadersInit = { Accept: 'application/vnd.github+json' };

/**
 * Map a release asset filename to one of the three OS buckets.
 *  - `.dmg` → mac
 *  - `.exe` → win
 *  - `.AppImage` → linux (preferred)
 *  - `.deb` → linux (fallback)
 * Anything else is ignored.
 */
export function classifyAsset(name: string): OsId | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('.dmg')) return 'mac';
  if (lower.endsWith('.exe')) return 'win';
  if (lower.endsWith('.appimage')) return 'linux';
  if (lower.endsWith('.deb')) return 'linux';
  return null;
}

type GitHubRelease = {
  tag_name: string;
  html_url: string;
  assets: { name: string; browser_download_url: string; size: number }[];
};

/**
 * Fetch and parse the latest release. Throws on any non-2xx (other than
 * 404, which yields `null` to distinguish "no release" from "the API is
 * down"). Network failures also throw.
 */
export async function fetchLatestRelease(
  fetchImpl: typeof fetch = fetch,
  url: string = RELEASES_URL,
): Promise<Release | null> {
  const res = await fetchImpl(url, { headers: GITHUB_API_HEADERS });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`releases: ${res.status}`);
  const data = (await res.json()) as GitHubRelease;
  const assets: ReleaseAsset[] = [];
  for (const a of data.assets ?? []) {
    const os = classifyAsset(a.name);
    if (!os) continue;
    assets.push({ os, name: a.name, url: a.browser_download_url, size: a.size });
  }
  return { tag: data.tag_name, htmlUrl: data.html_url, assets };
}

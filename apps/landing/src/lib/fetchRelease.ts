/**
 * GitHub Releases fetch for the postcard installer artifacts.
 *
 * The landing page calls the unauthenticated REST endpoint to discover
 * the most recent release's asset URLs and sizes, then hands them to the
 * Download component. Errors and 404s (no release published yet) are
 * expected states — callers handle them by falling back to a
 * "Coming soon" message rather than a toast / crash.
 */

import type { OsId } from './detectOs';

export const RELEASES_URL = 'https://api.github.com/repos/hectortav/postcard/releases/latest';

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
 *  - `.msi` → win (what the release workflow actually builds)
 *  - `.exe` → win (an installer shape we do not ship today, accepted anyway)
 *  - `.AppImage` → linux (preferred, not built today)
 *  - `.deb` → linux (what the release workflow actually builds)
 * Anything else is ignored.
 *
 * `.msi` used to be missing, and it is the only Windows artifact the pipeline produces
 * (`jpackageMsi`, `--type msi`). The effect was not a wrong label but a dead button: with no
 * asset classified as `win`, the Windows download rendered as "not in this release" for every
 * release ever cut, and a unit test fixture using a `.exe` name the build never emits kept
 * the suite green.
 */
export function classifyAsset(name: string): OsId | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('.dmg')) return 'mac';
  if (lower.endsWith('.msi')) return 'win';
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

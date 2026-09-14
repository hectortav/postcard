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

/** How long a fetched release stays good for. */
const CACHE_TTL_MS = 30 * 60 * 1000;
const CACHE_KEY = 'postcard:latest-release';

type Cached = { at: number; release: Release | null };

/**
 * Remember the last answer for half an hour.
 *
 * The GitHub API allows 60 unauthenticated requests an hour per address, and this page asks
 * on every load. A handful of people behind one office address is enough to exhaust that, and
 * the page then shows "Coming soon" for a release that exists. Session storage is per tab and
 * survives reloads, which is the shape of the problem.
 */
function readCache(): Cached | null {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Cached;
    if (typeof parsed?.at !== 'number') return null;
    if (Date.now() - parsed.at > CACHE_TTL_MS) return null;
    return parsed;
  } catch {
    // Private windows and blocked site data both throw here; a miss is the right answer.
    return null;
  }
}

function writeCache(release: Release | null): void {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), release }));
  } catch {
    // Not being able to cache is not a reason to fail the page.
  }
}

/**
 * Fetch and parse the latest release. Throws on any non-2xx (other than
 * 404, which yields `null` to distinguish "no release" from "the API is
 * down"). Network failures also throw.
 */
export async function fetchLatestRelease(
  fetchImpl: typeof fetch = fetch,
  url: string = RELEASES_URL,
): Promise<Release | null> {
  const cached = readCache();
  if (cached) return cached.release;
  const res = await fetchImpl(url, { headers: GITHUB_API_HEADERS });
  if (res.status === 404) {
    writeCache(null);
    return null;
  }
  if (!res.ok) throw new Error(`releases: ${res.status}`);
  const data = (await res.json()) as GitHubRelease;
  const assets: ReleaseAsset[] = [];
  for (const a of data.assets ?? []) {
    const os = classifyAsset(a.name);
    if (!os) continue;
    assets.push({ os, name: a.name, url: a.browser_download_url, size: a.size });
  }
  const release = { tag: data.tag_name, htmlUrl: data.html_url, assets };
  writeCache(release);
  return release;
}

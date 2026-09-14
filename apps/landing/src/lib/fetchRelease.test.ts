import { describe, it, expect, vi } from 'vitest';
import { fetchLatestRelease, classifyAsset } from './fetchRelease';

describe('classifyAsset', () => {
  it('maps dmg/msi/exe/appimage/deb to mac/win/win/linux/linux', () => {
    expect(classifyAsset('postcard-0.1.0.dmg')).toBe('mac');
    expect(classifyAsset('postcard-0.1.0-x86_64.dmg')).toBe('mac');
    // .msi is what `jpackageMsi` produces and therefore what every release actually
    // contains; the fixture below used a .exe name the build has never emitted.
    expect(classifyAsset('postcard-1.0.msi')).toBe('win');
    expect(classifyAsset('postcard-setup-0.1.0.exe')).toBe('win');
    expect(classifyAsset('postcard-0.1.0.AppImage')).toBe('linux');
    expect(classifyAsset('postcard_0.1.0_amd64.deb')).toBe('linux');
  });

  it('is case-insensitive on the extension', () => {
    expect(classifyAsset('FOO.DMG')).toBe('mac');
    expect(classifyAsset('FOO.APPIMAGE')).toBe('linux');
  });

  it('returns null for unknown extensions', () => {
    expect(classifyAsset('postcard.tar.gz')).toBeNull();
    expect(classifyAsset('README.md')).toBeNull();
  });
});

describe('fetchLatestRelease', () => {
  it('returns null on 404 (no release yet)', async () => {
    const fetchImpl = vi.fn(async () => new Response('Not Found', { status: 404 }));
    const r = await fetchLatestRelease(fetchImpl as unknown as typeof fetch);
    expect(r).toBeNull();
  });

  it('parses a 200 response into a Release with classified assets', async () => {
    const body = {
      tag_name: 'v0.1.0',
      html_url: 'https://github.com/hectortav/postcard/releases/tag/v0.1.0',
      assets: [
        { name: 'postcard-0.1.0.dmg', browser_download_url: 'https://x/mac.dmg', size: 4_000_000 },
        { name: 'postcard-1.0.msi', browser_download_url: 'https://x/win.msi', size: 3_000_000 },
        {
          name: 'postcard-0.1.0.AppImage',
          browser_download_url: 'https://x/linux.AppImage',
          size: 5_000_000,
        },
        {
          name: 'postcard-0.1.0.tar.gz',
          browser_download_url: 'https://x/src.tar.gz',
          size: 100_000,
        },
      ],
    };
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
    const r = await fetchLatestRelease(fetchImpl as unknown as typeof fetch);
    expect(r).not.toBeNull();
    expect(r?.tag).toBe('v0.1.0');
    expect(r?.assets).toHaveLength(3);
    const byOs = Object.fromEntries(r!.assets.map((a) => [a.os, a] as const));
    expect(byOs.mac?.name).toBe('postcard-0.1.0.dmg');
    expect(byOs.win?.name).toBe('postcard-1.0.msi');
    expect(byOs.linux?.name).toBe('postcard-0.1.0.AppImage');
  });

  it('throws on non-2xx responses other than 404', async () => {
    const fetchImpl = vi.fn(async () => new Response('boom', { status: 500 }));
    await expect(fetchLatestRelease(fetchImpl as unknown as typeof fetch)).rejects.toThrow(/500/);
  });

  it('throws on network failure', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    await expect(fetchLatestRelease(fetchImpl as unknown as typeof fetch)).rejects.toThrow();
  });
  it('handles a release that has no assets yet', async () => {
    // A tag is published before the installer jobs finish uploading, so the API briefly
    // returns a release with `assets` absent. The download section must render empty rather
    // than throw.
    const body = {
      tag_name: 'v0.1.0',
      html_url: 'https://github.com/hectortav/postcard/releases/tag/v0.1.0',
    };
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
    const r = await fetchLatestRelease(fetchImpl as unknown as typeof fetch);
    expect(r?.tag).toBe('v0.1.0');
    expect(r?.assets).toEqual([]);
  });
});

describe('release caching', () => {
  const body = {
    tag_name: 'v9.9.9',
    html_url: 'https://x/rel',
    assets: [{ name: 'postcard-9.9.9.dmg', browser_download_url: 'https://x/mac.dmg', size: 1 }],
  };

  it('asks GitHub once and serves the rest from the cache', async () => {
    // 60 unauthenticated requests an hour, per address. One office behind one address is
    // enough to exhaust that and make a real release render as "Coming soon".
    let calls = 0;
    const fetchImpl = (async () => {
      calls++;
      return { ok: true, status: 200, json: async () => body } as Response;
    }) as unknown as typeof fetch;

    const first = await fetchLatestRelease(fetchImpl, 'https://api/x');
    const second = await fetchLatestRelease(fetchImpl, 'https://api/x');
    expect(calls).toBe(1);
    expect(second).toEqual(first);
  });

  it('caches the absence of a release too', async () => {
    let calls = 0;
    const fetchImpl = (async () => {
      calls++;
      return { ok: false, status: 404 } as Response;
    }) as unknown as typeof fetch;
    expect(await fetchLatestRelease(fetchImpl, 'https://api/x')).toBeNull();
    expect(await fetchLatestRelease(fetchImpl, 'https://api/x')).toBeNull();
    expect(calls).toBe(1);
  });

  it('does not cache a failure, so a blip is retried', async () => {
    let calls = 0;
    const fetchImpl = (async () => {
      calls++;
      return { ok: false, status: 503 } as Response;
    }) as unknown as typeof fetch;
    await expect(fetchLatestRelease(fetchImpl, 'https://api/x')).rejects.toThrow();
    await expect(fetchLatestRelease(fetchImpl, 'https://api/x')).rejects.toThrow();
    expect(calls).toBe(2);
  });
});

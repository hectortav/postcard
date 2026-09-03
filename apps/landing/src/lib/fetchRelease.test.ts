import { describe, it, expect, vi } from 'vitest';
import { fetchLatestRelease, classifyAsset } from './fetchRelease';

describe('classifyAsset', () => {
  it('maps dmg/exe/appimage/deb to mac/win/linux/linux', () => {
    expect(classifyAsset('sendme-0.1.0.dmg')).toBe('mac');
    expect(classifyAsset('sendme-0.1.0-x86_64.dmg')).toBe('mac');
    expect(classifyAsset('sendme-setup-0.1.0.exe')).toBe('win');
    expect(classifyAsset('sendme-0.1.0.AppImage')).toBe('linux');
    expect(classifyAsset('sendme_0.1.0_amd64.deb')).toBe('linux');
  });

  it('is case-insensitive on the extension', () => {
    expect(classifyAsset('FOO.DMG')).toBe('mac');
    expect(classifyAsset('FOO.APPIMAGE')).toBe('linux');
  });

  it('returns null for unknown extensions', () => {
    expect(classifyAsset('sendme.tar.gz')).toBeNull();
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
      html_url: 'https://github.com/index-zr0/sendme/releases/tag/v0.1.0',
      assets: [
        { name: 'sendme-0.1.0.dmg', browser_download_url: 'https://x/mac.dmg', size: 4_000_000 },
        { name: 'sendme-setup-0.1.0.exe', browser_download_url: 'https://x/win.exe', size: 3_000_000 },
        { name: 'sendme-0.1.0.AppImage', browser_download_url: 'https://x/linux.AppImage', size: 5_000_000 },
        { name: 'sendme-0.1.0.tar.gz', browser_download_url: 'https://x/src.tar.gz', size: 100_000 },
      ],
    };
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
    const r = await fetchLatestRelease(fetchImpl as unknown as typeof fetch);
    expect(r).not.toBeNull();
    expect(r?.tag).toBe('v0.1.0');
    expect(r?.assets).toHaveLength(3);
    const byOs = Object.fromEntries(r!.assets.map((a) => [a.os, a]));
    expect(byOs.mac.name).toBe('sendme-0.1.0.dmg');
    expect(byOs.win.name).toBe('sendme-setup-0.1.0.exe');
    expect(byOs.linux.name).toBe('sendme-0.1.0.AppImage');
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
});

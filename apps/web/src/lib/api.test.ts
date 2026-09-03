import { describe, it, expect, vi, beforeEach } from 'vitest';
import { listFiles, uploadFile, getClipboard } from './api';

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('api', () => {
  it('listFiles calls /api/files and returns the array', async () => {
    const spy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(
        new Response(
          JSON.stringify([{ id: 'a', name: 'a.txt', size: 4, mtime: 0, sha256: 'x' }]),
        ),
      );
    const out = await listFiles();
    expect(out).toHaveLength(1);
    expect(spy.mock.calls[0]?.[0]).toBe('/api/files');
  });

  it('getClipboard reads the text field', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ text: 'hello' })));
    expect(await getClipboard()).toBe('hello');
  });

  it('uploadFile posts FormData and parses the JSON id', async () => {
    type XhrLike = {
      open: ReturnType<typeof vi.fn>;
      send: ReturnType<typeof vi.fn>;
      upload: { onprogress: ((ev: ProgressEvent) => void) | null };
      onload: (() => void) | null;
      onerror: (() => void) | null;
      status: number;
      responseText: string;
    };
    const xhr: XhrLike = {
      open: vi.fn(),
      send: vi.fn(),
      upload: { onprogress: null },
      onload: null,
      onerror: null,
      status: 200,
      responseText: '{"id":"abc"}',
    };
    const ctor = vi.fn().mockReturnValue(xhr);
    (globalThis as unknown as { XMLHttpRequest: typeof ctor }).XMLHttpRequest = ctor;
    const p = uploadFile(new File(['x'], 'a.txt'));
    xhr.onload?.();
    await expect(p).resolves.toEqual({ id: 'abc' });
    expect(ctor).toHaveBeenCalled();
  });
});

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { listFiles, uploadFile, getClipboard } from './api';

beforeEach(() => {
  vi.restoreAllMocks();
});


type XhrLike = {
  open: ReturnType<typeof vi.fn>;
  send: ReturnType<typeof vi.fn>;
  upload: { onprogress: ((ev: ProgressEvent) => void) | null };
  onload: (() => void) | null;
  onerror: (() => void) | null;
  status: number;
  responseText: string;
};

/**
 * Install a stub XMLHttpRequest and hand back the instance the code under test will use.
 *
 * vitest 5 refuses `mockReturnValue` for anything invoked with `new`, so the constructor mock
 * has to be a real class that yields the stub instance.
 */
function stubXhr(status: number, responseText: string): XhrLike {
  const xhr: XhrLike = {
    open: vi.fn(),
    send: vi.fn(),
    upload: { onprogress: null },
    onload: null,
    onerror: null,
    status,
    responseText,
  };
  // oxlint-disable-next-line typescript/no-extraneous-class
  const ctor = vi.fn(class {
    constructor() {
      return xhr as unknown as XMLHttpRequest;
    }
  });
  (globalThis as unknown as { XMLHttpRequest: typeof ctor }).XMLHttpRequest = ctor;
  return xhr;
}

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
    const xhr = stubXhr(200, '{"id":"abc"}');
    const p = uploadFile(new File(['x'], 'a.txt'));
    xhr.onload?.();
    await expect(p).resolves.toEqual({ id: 'abc' });
    expect(xhr.open).toHaveBeenCalledWith('POST', '/api/upload');
    expect(xhr.send).toHaveBeenCalled();
  });

  it('listFiles throws when the server rejects the request', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('nope', { status: 503 }));
    await expect(listFiles()).rejects.toThrow('listFiles: 503');
  });

  it('getClipboard throws when the server rejects the request', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('nope', { status: 500 }));
    await expect(getClipboard()).rejects.toThrow('clipboard: 500');
  });

  it('getClipboard yields an empty string when the field is absent', async () => {
    // The server omits `text` when nothing has been shared yet; the UI must not render
    // "undefined" into the clipboard box.
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({})));
    expect(await getClipboard()).toBe('');
  });

  it('uploadFile reports progress to the callback', async () => {
    const xhr = stubXhr(200, '{"id":"abc"}');
    const seen: number[] = [];
    const p = uploadFile(new File(['x'], 'a.txt'), (loaded) => seen.push(loaded));
    xhr.upload.onprogress?.({ loaded: 512 } as ProgressEvent);
    xhr.onload?.();
    await expect(p).resolves.toEqual({ id: 'abc' });
    expect(seen).toEqual([512]);
  });

  it('uploadFile surfaces the size limit when the server rejects an oversized file', async () => {
    // The 413 body is structured so the UI can tell the user the actual cap rather than a
    // bare status code.
    const xhr = stubXhr(413, JSON.stringify({ error: 'upload_too_large', limitBytes: 1048576 }));
    const p = uploadFile(new File(['x'], 'big.bin'));
    xhr.onload?.();
    await expect(p).rejects.toThrow('upload: 413 limitBytes=1048576');
  });

  it('uploadFile still reports the limit error when the server omits limitBytes', async () => {
    const xhr = stubXhr(413, JSON.stringify({ error: 'upload_too_large' }));
    const p = uploadFile(new File(['x'], 'big.bin'));
    xhr.onload?.();
    await expect(p).rejects.toThrow('upload: 413 limitBytes=?');
  });

  it('uploadFile falls back to a generic error when the body is not JSON', async () => {
    const xhr = stubXhr(500, '<html>gateway</html>');
    const p = uploadFile(new File(['x'], 'a.txt'));
    xhr.onload?.();
    await expect(p).rejects.toThrow('upload: 500');
  });

  it('uploadFile falls back to a generic error for unrelated structured errors', async () => {
    const xhr = stubXhr(400, JSON.stringify({ error: 'something_else' }));
    const p = uploadFile(new File(['x'], 'a.txt'));
    xhr.onload?.();
    await expect(p).rejects.toThrow('upload: 400');
  });

  it('uploadFile rejects when a 200 body cannot be parsed', async () => {
    const xhr = stubXhr(200, 'not json');
    const p = uploadFile(new File(['x'], 'a.txt'));
    xhr.onload?.();
    await expect(p).rejects.toThrow();
  });

  it('uploadFile rejects on a network failure', async () => {
    const xhr = stubXhr(0, '');
    const p = uploadFile(new File(['x'], 'a.txt'));
    xhr.onerror?.();
    await expect(p).rejects.toThrow('upload: network');
  });
});

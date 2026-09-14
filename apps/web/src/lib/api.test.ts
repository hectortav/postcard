import { describe, it, expect, vi, afterEach } from 'vitest';
import { uploadFile, UploadError, listFiles, getClipboard } from './api';

/**
 * Install a stub XMLHttpRequest and hand back the instance the code under test will use.
 * `XMLHttpRequest` is constructed inside uploadFile, so the global has to be a real class
 * that yields the stub instance.
 */
type XhrLike = {
  status: number;
  responseText: string;
  upload: { onprogress?: (ev: { loaded: number; lengthComputable: boolean }) => void };
  onload?: () => void;
  onerror?: () => void;
  onabort?: () => void;
  open: (m: string, u: string) => void;
  send: (b: unknown) => void;
  abort: () => void;
  sent?: unknown;
};

function stubXhr(status: number, responseText: string, opts: { autoload?: boolean } = {}): XhrLike {
  const xhr: XhrLike = {
    status,
    responseText,
    upload: {},
    open: () => {},
    send(body) {
      xhr.sent = body;
      if (opts.autoload !== false) queueMicrotask(() => xhr.onload?.());
    },
    abort() {
      queueMicrotask(() => xhr.onabort?.());
    },
  };
  (globalThis as unknown as { XMLHttpRequest: unknown }).XMLHttpRequest = function () {
    return xhr;
  };
  return xhr;
}

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

describe('uploadFile', () => {
  it('resolves with the id the server assigns', async () => {
    stubXhr(200, '{"id":"abc"}');
    expect(await uploadFile(new File(['x'], 'a.txt'))).toEqual({ id: 'abc' });
  });

  it('streams the File itself rather than reading it into memory', async () => {
    // A multi-gigabyte upload must never enter the JavaScript heap: handing FormData the
    // File lets the browser stream it from disk.
    const xhr = stubXhr(200, '{"id":"abc"}');
    const file = new File(['x'], 'a.txt');
    await uploadFile(file);
    expect(xhr.sent).toBeInstanceOf(FormData);
    expect((xhr.sent as FormData).get('file')).toBe(file);
  });

  it('reports progress only when the length is known', async () => {
    // Without the lengthComputable check the bar is driven by a total that does not exist and
    // runs past 100%.
    const xhr = stubXhr(200, '{"id":"abc"}', { autoload: false });
    const seen: number[] = [];
    const promise = uploadFile(new File(['abcd'], 'a.txt'), (n) => seen.push(n));
    xhr.upload.onprogress?.({ loaded: 2, lengthComputable: true });
    xhr.upload.onprogress?.({ loaded: 3, lengthComputable: false });
    xhr.onload?.();
    await promise;
    expect(seen).toEqual([2]);
  });

  it('names the limit when the server rejects an oversized file', async () => {
    stubXhr(413, JSON.stringify({ error: 'upload_too_large', limitBytes: 1048576 }));
    const err = await uploadFile(new File(['x'], 'big.bin')).catch((e) => e);
    expect(err).toBeInstanceOf(UploadError);
    expect(err.kind).toBe('too_large');
    expect(err.limitBytes).toBe(1048576);
    // The user used to be shown the raw string "upload: 413 limitBytes=1048576".
    expect(err.message).toMatch(/1 MiB limit/);
  });

  it('still explains an oversized file when the server omits the limit', async () => {
    stubXhr(413, JSON.stringify({ error: 'upload_too_large' }));
    const err = await uploadFile(new File(['x'], 'big.bin')).catch((e) => e);
    expect(err.kind).toBe('too_large');
    expect(err.message).toMatch(/larger than this postcard accepts/);
  });

  it('asks for the PIN when the upload is gated', async () => {
    stubXhr(401, JSON.stringify({ error: 'pin_required' }));
    const err = await uploadFile(new File(['x'], 'a.txt')).catch((e) => e);
    expect(err.message).toMatch(/Enter the PIN/);
  });

  it('falls back to a readable message when the body is not JSON', async () => {
    stubXhr(500, '<html>gateway</html>');
    const err = await uploadFile(new File(['x'], 'a.txt')).catch((e) => e);
    expect(err.kind).toBe('server');
    expect(err.message).toMatch(/refused the upload \(500\)/);
  });

  it('reports a network failure as one', async () => {
    const xhr = stubXhr(0, '', { autoload: false });
    const promise = uploadFile(new File(['x'], 'a.txt'));
    xhr.onerror?.();
    const err = await promise.catch((e) => e);
    expect(err.kind).toBe('network');
  });

  it('can be cancelled mid-flight', async () => {
    const xhr = stubXhr(200, '{"id":"abc"}', { autoload: false });
    const controller = new AbortController();
    const promise = uploadFile(new File(['x'], 'a.txt'), undefined, controller.signal);
    controller.abort();
    const err = await promise.catch((e) => e);
    expect(err).toBeInstanceOf(UploadError);
    expect(err.kind).toBe('cancelled');
    expect(xhr.sent).toBeInstanceOf(FormData);
  });

  it('refuses immediately when the signal is already aborted', async () => {
    stubXhr(200, '{"id":"abc"}', { autoload: false });
    const err = await uploadFile(new File(['x'], 'a.txt'), undefined, AbortSignal.abort()).catch(
      (e) => e,
    );
    expect(err.kind).toBe('cancelled');
  });

  it('reports an unreadable success body rather than resolving with nothing', async () => {
    stubXhr(200, 'not json');
    const err = await uploadFile(new File(['x'], 'a.txt')).catch((e) => e);
    expect(err.kind).toBe('server');
  });
});

describe('listFiles and getClipboard', () => {
  it('parse a successful response', async () => {
    globalThis.fetch = vi.fn(async (url) =>
      String(url).includes('clipboard')
        ? ({ ok: true, json: async () => ({ text: 'hi' }) } as Response)
        : ({ ok: true, json: async () => [{ id: 'a' }] } as unknown as Response),
    ) as typeof fetch;
    expect(await listFiles()).toEqual([{ id: 'a' }]);
    expect(await getClipboard()).toBe('hi');
  });

  it('throw on a failed response', async () => {
    globalThis.fetch = vi.fn(async () => ({ ok: false, status: 503 }) as Response) as typeof fetch;
    await expect(listFiles()).rejects.toThrow(/503/);
    await expect(getClipboard()).rejects.toThrow(/503/);
  });

  it('treat a clipboard reply with no text as empty', async () => {
    globalThis.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({}) }) as Response,
    ) as typeof fetch;
    expect(await getClipboard()).toBe('');
  });
});

import type { FileEntry } from '../types';

/** A failed upload, with enough detail for the UI to say something useful. */
export class UploadError extends Error {
  readonly kind: 'too_large' | 'network' | 'cancelled' | 'server';
  readonly limitBytes?: number;
  constructor(kind: UploadError['kind'], message: string, limitBytes?: number) {
    super(message);
    this.name = 'UploadError';
    this.kind = kind;
    if (limitBytes !== undefined) this.limitBytes = limitBytes;
  }
}

function humanBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KiB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(0)} MiB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(1)} GiB`;
}

export async function listFiles(signal?: AbortSignal): Promise<FileEntry[]> {
  const r = await fetch('/api/files', { cache: 'no-store', ...(signal ? { signal } : {}) });
  if (!r.ok) throw new Error(`listFiles: ${r.status}`);
  return r.json() as Promise<FileEntry[]>;
}

/**
 * Upload one file.
 *
 * XHR rather than fetch, because only XHR reports upload progress, and the `File` is handed
 * to `FormData` untouched so the browser streams it from disk: a multi-gigabyte upload never
 * enters the JavaScript heap.
 *
 * Accepts an `AbortSignal`, which it previously did not. There was no way to stop an upload
 * once started, so a 2 GB file dropped by mistake had to run to completion.
 */
export function uploadFile(
  file: File,
  onProgress?: (loaded: number) => void,
  signal?: AbortSignal,
): Promise<{ id: string }> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new UploadError('cancelled', 'Upload cancelled.'));
      return;
    }
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload');

    const onAbort = () => xhr.abort();
    signal?.addEventListener('abort', onAbort, { once: true });
    const cleanup = () => signal?.removeEventListener('abort', onAbort);

    xhr.upload.onprogress = (ev) => {
      // lengthComputable is false for a stream of unknown size; reporting ev.loaded then
      // produces a progress bar that runs past 100%.
      if (ev.lengthComputable) onProgress?.(ev.loaded);
    };
    xhr.onabort = () => {
      cleanup();
      reject(new UploadError('cancelled', 'Upload cancelled.'));
    };
    xhr.onerror = () => {
      cleanup();
      reject(new UploadError('network', 'Lost the connection to postcard.'));
    };
    xhr.onload = () => {
      cleanup();
      const text = xhr.responseText;
      if (xhr.status === 200) {
        try {
          resolve(JSON.parse(text) as { id: string });
        } catch {
          reject(new UploadError('server', 'postcard sent a reply we could not read.'));
        }
        return;
      }
      let parsed: { error?: string; limitBytes?: number } | null = null;
      try {
        parsed = JSON.parse(text) as { error?: string; limitBytes?: number };
      } catch {
        parsed = null;
      }
      if (parsed?.error === 'upload_too_large') {
        const limit = parsed.limitBytes;
        reject(
          new UploadError(
            'too_large',
            limit === undefined
              ? 'That file is larger than this postcard accepts.'
              : `That file is larger than the ${humanBytes(limit)} limit this postcard accepts.`,
            limit,
          ),
        );
        return;
      }
      if (parsed?.error === 'pin_required') {
        reject(new UploadError('server', 'Enter the PIN before sending files.'));
        return;
      }
      reject(new UploadError('server', `postcard refused the upload (${xhr.status}).`));
    };

    const fd = new FormData();
    fd.append('file', file);
    xhr.send(fd);
  });
}

export async function getClipboard(signal?: AbortSignal): Promise<string> {
  const r = await fetch('/api/clipboard', { cache: 'no-store', ...(signal ? { signal } : {}) });
  if (!r.ok) throw new Error(`clipboard: ${r.status}`);
  const j = (await r.json()) as { text?: string };
  return j.text ?? '';
}

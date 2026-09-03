import type { FileEntry } from '../types';

export async function listFiles(): Promise<FileEntry[]> {
  const r = await fetch('/api/files', { cache: 'no-store' });
  if (!r.ok) throw new Error(`listFiles: ${r.status}`);
  return r.json() as Promise<FileEntry[]>;
}

export async function uploadFile(
  file: File,
  onProgress?: (loaded: number) => void,
): Promise<{ id: string }> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload');
    xhr.upload.onprogress = (ev) => onProgress?.(ev.loaded);
    xhr.onload = () => {
      const text = xhr.responseText;
      if (xhr.status === 200) {
        try {
          resolve(JSON.parse(text) as { id: string });
        } catch (e) {
          reject(e as Error);
        }
        return;
      }
      // Try to surface a structured 413 error if the server sent one
      try {
        const j = JSON.parse(text) as { error?: string; limitBytes?: number };
        if (j?.error === 'upload_too_large') {
          reject(new Error(`upload: ${xhr.status} limitBytes=${j.limitBytes ?? '?'}`));
          return;
        }
      } catch {
        // fall through to generic error
      }
      reject(new Error(`upload: ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('upload: network'));
    const fd = new FormData();
    fd.append('file', file);
    xhr.send(fd);
  });
}

export async function getClipboard(): Promise<string> {
  const r = await fetch('/api/clipboard', { cache: 'no-store' });
  if (!r.ok) throw new Error(`clipboard: ${r.status}`);
  const j = (await r.json()) as { text?: string };
  return j.text ?? '';
}

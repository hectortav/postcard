import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, waitFor, cleanup } from '@testing-library/preact';
import { gcm } from '@noble/ciphers/aes.js';
import { FileList } from './FileList';
import { chunkAad } from '../lib/decrypt';
import { WARN_ABOVE_BYTES } from '../lib/decryptStream';

// Without this the DOM from one test is still mounted during the next, so a query for "no
// links here" finds links belonging to a render that already finished.
afterEach(cleanup);

describe('FileList', () => {
  it('renders an empty state when no files', () => {
    const { getByText } = render(<FileList files={[]} />);
    expect(getByText(/no files yet/i)).toBeTruthy();
  });
  it('renders a row per file with a download link', () => {
    const { getAllByRole } = render(
      <FileList files={[{ id: 'a', name: 'a.txt', size: 4, mtime: 0, sha256: 'x' }]} />,
    );
    const links = getAllByRole('link') as HTMLAnchorElement[];
    expect(links[0]!.getAttribute('href')).toBe('/api/download/a');
  });
  it('scales the size unit to the file', () => {
    // formatBytes picks a unit per magnitude; a 3 GiB video showing as "3221225472 B" would be
    // unreadable on a phone.
    const { getByText } = render(
      <FileList
        files={[
          { id: 'a', name: 'tiny.txt', size: 512, mtime: 0, sha256: 'x' },
          { id: 'b', name: 'small.txt', size: 2048, mtime: 0, sha256: 'x' },
          { id: 'c', name: 'medium.bin', size: 5 * 1024 * 1024, mtime: 0, sha256: 'x' },
          { id: 'd', name: 'huge.mov', size: 3 * 1024 * 1024 * 1024, mtime: 0, sha256: 'x' },
        ]}
      />,
    );
    expect(getByText(/512 B/)).toBeTruthy();
    expect(getByText(/2\.0 KiB/)).toBeTruthy();
    expect(getByText(/5\.0 MiB/)).toBeTruthy();
    expect(getByText(/3\.00 GiB/)).toBeTruthy();
  });

  describe('when the server sends this client ciphertext', () => {
    const KEY = new Uint8Array(32).fill(7);
    const PLAINTEXT = new TextEncoder().encode('the real contents');
    const FILE = {
      id: 'abc12345',
      name: 'secret.txt',
      size: PLAINTEXT.length,
      mtime: 0,
      sha256: 'x',
    };

    const originalFetch = globalThis.fetch;
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;

    afterEach(() => {
      globalThis.fetch = originalFetch;
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
      vi.restoreAllMocks();
    });

    function serveEncrypted(fileId = FILE.id) {
      const nonce = new Uint8Array(12).fill(1);
      const ct = gcm(KEY, nonce, chunkAad(0, true, fileId)).encrypt(PLAINTEXT);
      const wire = new Uint8Array(nonce.length + ct.length);
      wire.set(nonce, 0);
      wire.set(ct, nonce.length);
      globalThis.fetch = vi.fn(async () => ({
        ok: true,
        body: new ReadableStream<Uint8Array>({
          start(c) {
            c.enqueue(wire);
            c.close();
          },
        }),
      })) as unknown as typeof fetch;
    }

    it('offers a button rather than a plain link, because a link would save ciphertext', () => {
      // This is the bug in one line: <a download> on an encrypted session hands the receiver
      // a file they cannot open, which is what shipped.
      const { queryAllByRole, getByRole } = render(
        <FileList files={[FILE]} encrypted downloadKey={KEY} />,
      );
      expect(queryAllByRole('link')).toHaveLength(0);
      expect(getByRole('button', { name: /secret\.txt/ })).toBeTruthy();
    });

    it('decrypts and saves the plaintext', async () => {
      serveEncrypted();
      let saved: Blob | null = null;
      URL.createObjectURL = vi.fn((b: Blob) => {
        saved = b;
        return 'blob:x';
      }) as typeof URL.createObjectURL;
      URL.revokeObjectURL = vi.fn();

      const { getByRole } = render(<FileList files={[FILE]} encrypted downloadKey={KEY} />);
      fireEvent.click(getByRole('button', { name: /secret\.txt/ }));

      await waitFor(() => expect(saved).not.toBeNull());
      expect(new TextDecoder().decode(await saved!.arrayBuffer())).toBe('the real contents');
    });

    it('reports a file that fails to verify instead of saving it', async () => {
      serveEncrypted('a-different-file');
      const { getByRole, findByRole } = render(
        <FileList files={[FILE]} encrypted downloadKey={KEY} />,
      );
      fireEvent.click(getByRole('button', { name: /secret\.txt/ }));
      expect((await findByRole('alert')).textContent).toMatch(/verify/i);
    });

    it('says so when the link carries no key', async () => {
      const { getByRole, findByRole } = render(
        <FileList files={[FILE]} encrypted downloadKey={null} />,
      );
      fireEvent.click(getByRole('button', { name: /secret\.txt/ }));
      expect((await findByRole('alert')).textContent).toMatch(/missing its key/i);
    });

    it('warns before decrypting something a phone may not be able to hold', async () => {
      const big = { ...FILE, size: WARN_ABOVE_BYTES + 1 };
      const { getByRole, findByText } = render(
        <FileList files={[big]} encrypted downloadKey={KEY} />,
      );
      fireEvent.click(getByRole('button', { name: /secret\.txt/ }));
      expect(await findByText(/may be more than this browser will hold/i)).toBeTruthy();
    });

    it('still uses a plain link when the server sends plaintext', () => {
      // The host's own window: the server already knows it does not need to encrypt for it,
      // so the download streams straight to disk and keeps resume support.
      const { getAllByRole } = render(<FileList files={[FILE]} encrypted={false} />);
      expect((getAllByRole('link')[0] as HTMLAnchorElement).getAttribute('href')).toBe(
        '/api/download/abc12345',
      );
    });
  });
});

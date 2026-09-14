import { useCallback, useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import type { FileEntry } from '../types';
import { decryptToBlob, WARN_ABOVE_BYTES, DecryptionError } from '../lib/decryptStream';

type Props = {
  files: FileEntry[];
  /** True when this client's downloads arrive encrypted; the server decides, not the page. */
  encrypted?: boolean;
  /** The AES key for this session, once known. Held in memory only. */
  downloadKey?: Uint8Array | null;
};

type Progress =
  | { phase: 'idle' }
  | { phase: 'confirm'; id: string }
  | { phase: 'working'; id: string; done: number }
  | { phase: 'error'; id: string; message: string };

/**
 * Save a Blob under `name`, via the only route available in an insecure context: an object
 * URL and a synthetic click. Revoked on the next tick so the browser has taken its copy.
 */
function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export function FileList({ files, encrypted = false, downloadKey = null }: Props) {
  const [progress, setProgress] = useState<Progress>({ phase: 'idle' });

  const start = useCallback(
    async (file: FileEntry) => {
      if (!downloadKey) {
        setProgress({ phase: 'error', id: file.id, message: 'This link is missing its key.' });
        return;
      }
      setProgress({ phase: 'working', id: file.id, done: 0 });
      try {
        const res = await fetch(`/api/download/${file.id}`, { cache: 'no-store' });
        if (!res.ok || !res.body) throw new Error(`download ${res.status}`);
        const blob = await decryptToBlob(downloadKey, res.body, file.id, (done) =>
          setProgress({ phase: 'working', id: file.id, done }),
        );
        saveBlob(blob, file.name);
        setProgress({ phase: 'idle' });
      } catch (e) {
        setProgress({
          phase: 'error',
          id: file.id,
          message:
            e instanceof DecryptionError
              ? e.message
              : // The usual way this fails on a phone is running out of room to hold the
                // decrypted file, which no amount of retrying fixes.
                'Could not decrypt this file. It may be too large for this browser to hold.',
        });
      }
    },
    [downloadKey],
  );

  const onDownload = useCallback(
    (file: FileEntry) => {
      if (file.size > WARN_ABOVE_BYTES) setProgress({ phase: 'confirm', id: file.id });
      else void start(file);
    },
    [start],
  );

  if (files.length === 0) {
    return <p className={stylex(styles.empty)}>No files yet — drop one above.</p>;
  }

  return (
    <ul className={stylex(styles.list)}>
      {files.map((f, i) => {
        const mine = progress.phase !== 'idle' && progress.id === f.id ? progress : null;
        const working = mine?.phase === 'working';
        return (
          <li key={f.id} className={stylex(styles.row, i === files.length - 1 && styles.rowLast)}>
            <div className={stylex(styles.cell)}>
              {encrypted ? (
                <button
                  type="button"
                  onClick={() => onDownload(f)}
                  disabled={working}
                  aria-busy={working ? 'true' : 'false'}
                  className={stylex(styles.link, styles.buttonReset)}
                >
                  <span className={stylex(styles.linkName)}>{f.name}</span>
                  <span className={stylex(styles.linkHint)}>
                    {working
                      ? `decrypting… ${formatBytes(mine.done)} of ${formatBytes(f.size)}`
                      : 'tap to decrypt and save'}
                  </span>
                </button>
              ) : (
                <a href={`/api/download/${f.id}`} download={f.name} className={stylex(styles.link)}>
                  <span className={stylex(styles.linkName)}>{f.name}</span>
                  <span className={stylex(styles.linkHint)}>tap to download</span>
                </a>
              )}
              {mine?.phase === 'confirm' && (
                <p className={stylex(styles.notice)}>
                  This file has to be decrypted in memory before it can be saved, and at{' '}
                  {formatBytes(f.size)} it may be more than this browser will hold.{' '}
                  <button
                    type="button"
                    onClick={() => void start(f)}
                    className={stylex(styles.inlineButton)}
                  >
                    Try anyway
                  </button>
                </p>
              )}
              {mine?.phase === 'error' && (
                <p className={stylex(styles.error)} role="alert">
                  {mine.message}
                </p>
              )}
            </div>
            <span className={stylex(styles.size)}>{formatBytes(f.size)}</span>
          </li>
        );
      })}
    </ul>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MiB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GiB`;
}

const styles = stylex.create({
  cell: {
    display: 'flex',
    flexDirection: 'column',
    gap: '4px',
    minWidth: 0,
    flex: '1 1 auto',
  },
  buttonReset: {
    appearance: 'none',
    background: 'none',
    border: 'none',
    padding: 0,
    font: 'inherit',
    textAlign: 'left',
    cursor: 'pointer',
    width: '100%',
    ':disabled': { cursor: 'progress' },
  },
  notice: {
    margin: 0,
    fontSize: '12px',
    lineHeight: 1.5,
    color: '#4A443C',
  },
  inlineButton: {
    appearance: 'none',
    background: 'none',
    border: 'none',
    padding: 0,
    font: 'inherit',
    color: '#A8332A',
    textDecoration: 'underline',
    cursor: 'pointer',
  },
  error: {
    margin: 0,
    fontSize: '12px',
    lineHeight: 1.5,
    color: '#A8332A',
  },
  // Content sits flat on the sheet. Only interactive chrome gets depth, so the
  // list reads as printed rather than as a stack of cards.
  list: {
    listStyle: 'none',
    padding: 0,
    margin: '22px 0 0 0',
    borderTop: '1px solid #D8CDB7',
  },
  row: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: '12px',
    padding: '13px 8px',
    borderBottom: '1px solid #D8CDB7',
    minHeight: '56px',
    transitionProperty: 'background-color',
    transitionDuration: '120ms',
    ':hover': { backgroundColor: '#EFE7D8' },
  },
  rowLast: {},
  link: {
    display: 'flex',
    flexDirection: 'column',
    gap: '2px',
    minWidth: 0,
    textDecoration: 'none',
    color: '#1A1714',
    outline: 'none',
    borderRadius: '4px',
    ':focus-visible': { boxShadow: '0 0 0 3px rgba(168,51,42,0.30)' },
  },
  linkName: {
    fontSize: '15px',
    fontWeight: '500',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  linkHint: {
    fontSize: '12px',
    color: '#8C8474',
  },
  size: {
    fontSize: '13px',
    color: '#4A443C',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    flex: '0 0 auto',
  },
  // An invitation, not a shrug: the empty state points at the well above it.
  empty: {
    margin: '22px 0 0 0',
    padding: '20px 0',
    textAlign: 'center',
    fontSize: '14px',
    color: '#8C8474',
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
  },
});

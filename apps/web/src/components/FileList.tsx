import stylex from '@stylexjs/stylex';
import type { FileEntry } from '../types';

export function FileList({ files }: { files: FileEntry[] }) {
  if (files.length === 0) {
    return <p className={stylex(styles.empty)}>No files yet — drop one above.</p>;
  }
  return (
    <ul className={stylex(styles.list)}>
      {files.map((f, i) => (
        <li key={f.id} className={stylex(styles.row, i === files.length - 1 && styles.rowLast)}>
          <a href={`/api/download/${f.id}`} download={f.name} className={stylex(styles.link)}>
            <span className={stylex(styles.linkName)}>{f.name}</span>
            <span className={stylex(styles.linkHint)}>tap to download</span>
          </a>
          <span className={stylex(styles.size)}>{formatBytes(f.size)}</span>
        </li>
      ))}
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
  list: {
    listStyle: 'none',
    padding: 0,
    margin: '20px 0 0 0',
    backgroundColor: '#E9E1D0',
    borderRadius: '4px',
    overflow: 'hidden',
  },
  row: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: '12px',
    padding: '14px 16px',
    borderBottom: '1px solid #D8CDB7',
    minHeight: '56px',
  },
  rowLast: {
    borderBottom: 'none',
  },
  link: {
    display: 'flex',
    flexDirection: 'column',
    gap: '2px',
    color: '#1A1714',
    textDecoration: 'none',
    minWidth: 0,
    flex: '1 1 auto',
    padding: '4px 8px',
    margin: '-4px -8px',
    borderRadius: '2px',
    outline: 'none',
    ':hover': {
      color: '#A8332A',
    },
    ':hover span:first-child': {
      textDecoration: 'underline',
      textDecorationColor: '#A8332A',
      textUnderlineOffset: '2px',
    },
    ':focus-visible': {
      boxShadow: '0 0 0 2px #A8332A33',
    },
  },
  linkName: {
    fontSize: '15px',
    color: 'inherit',
    fontWeight: '500',
    wordBreak: 'break-all',
  },
  linkHint: {
    fontSize: '11px',
    color: '#8C8474',
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
  },
  size: {
    color: '#4A443C',
    fontSize: '13px',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    flexShrink: 0,
  },
  empty: {
    color: '#8C8474',
    fontSize: '14px',
    padding: '20px 16px',
    margin: '20px 0 0 0',
    textAlign: 'center',
    fontStyle: 'italic',
  },
});

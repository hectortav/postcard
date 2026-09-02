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

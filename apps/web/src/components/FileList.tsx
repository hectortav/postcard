import stylex from '@stylexjs/stylex';
import type { FileEntry } from '../types';

export function FileList({ files }: { files: FileEntry[] }) {
  if (files.length === 0) return <p className={stylex(styles.empty)}>No files yet — drop one above.</p>;
  return (
    <ul className={stylex(styles.list)}>
      {files.map((f) => (
        <li key={f.id} className={stylex(styles.row)}>
          <a href={`/api/download/${f.id}`} download={f.name} className={stylex(styles.link)}>{f.name}</a>
          <span className={stylex(styles.size)}>{formatBytes(f.size)}</span>
        </li>
      ))}
    </ul>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
  return `${(n / 1024 / 1024).toFixed(1)} MiB`;
}

const styles = stylex.create({
  empty: { color: '#8b95a5', padding: '16px' },
  list: { listStyle: 'none', padding: 0, margin: 0 },
  row: { display: 'flex', justifyContent: 'space-between', padding: '8px', borderBottom: `1px solid ${'#161a20'}` },
  link: { color: '#5aa9ff', textDecoration: 'none' },
  size: { color: '#8b95a5', fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace' },
});

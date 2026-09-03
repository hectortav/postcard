import { useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { uploadFile } from '../lib/api';

export function DropZone() {
  const [progress, setProgress] = useState<{ name: string; pct: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function onDrop(ev: DragEvent) {
    ev.preventDefault();
    setError(null);
    for (const file of Array.from(ev.dataTransfer?.files ?? [])) {
      setProgress({ name: file.name, pct: 0 });
      try {
        await uploadFile(file, (loaded) =>
          setProgress({ name: file.name, pct: Math.round((loaded / file.size) * 100) }),
        );
        setProgress(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        setProgress(null);
      }
    }
  }

  return (
    <div
      className={stylex(styles.zone)}
      onDragOver={(e) => e.preventDefault()}
      onDrop={onDrop}
    >
      <p>Drop files here to upload</p>
      {progress && <p>Uploading {progress.name}: {progress.pct}%</p>}
      {error && <p className={stylex(styles.error)}>{error}</p>}
    </div>
  );
}

const styles = stylex.create({
  zone: {
    padding: '24px',
    border: `2px dashed ${'#8b95a5'}`,
    borderRadius: '16px',
    textAlign: 'center',
  },
  error: { color: '#ff6b6b' },
});

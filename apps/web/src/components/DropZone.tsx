import { useRef } from 'preact/hooks';
import { useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { uploadFile } from '../lib/api';

export function DropZone() {
  const [progress, setProgress] = useState<{ name: string; pct: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  async function handleFiles(files: FileList | File[]) {
    setError(null);
    for (const file of Array.from(files)) {
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

  async function onDrop(ev: DragEvent) {
    ev.preventDefault();
    if (ev.dataTransfer?.files) await handleFiles(ev.dataTransfer.files);
  }

  function onPick(ev: Event) {
    const input = ev.currentTarget as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      void handleFiles(input.files);
      input.value = '';
    }
  }

  return (
    <div
      className={stylex(styles.zone)}
      onDragOver={(e) => e.preventDefault()}
      onDrop={onDrop}
      onClick={() => fileInputRef.current?.click()}
      role="button"
      tabIndex={0}
    >
      <p>Drop files here or click to choose</p>
      <input
        ref={fileInputRef}
        type="file"
        multiple
        onChange={onPick}
        style={{ display: 'none' }}
        aria-hidden="true"
      />
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
    cursor: 'pointer',
  },
  error: { color: '#ff6b6b' },
});

import { useRef } from 'preact/hooks';
import { useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { uploadFile } from '../lib/api';

export function DropZone() {
  const [progress, setProgress] = useState<{ name: string; pct: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hover, setHover] = useState(false);
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
    setHover(false);
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
      className={stylex(styles.zone, hover && styles.zoneHover)}
      onDragOver={(e) => {
        e.preventDefault();
        setHover(true);
      }}
      onDragLeave={() => setHover(false)}
      onDrop={onDrop}
      onClick={() => fileInputRef.current?.click()}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          fileInputRef.current?.click();
        }
      }}
      role="button"
      tabIndex={0}
      aria-label="Drop files here, or tap to choose"
    >
      <div className={stylex(styles.glyph)} aria-hidden="true">⤓</div>
      <p className={stylex(styles.headline)}>Drop files here, or tap to choose</p>
      <p className={stylex(styles.help)}>They will appear in the list below</p>
      <input
        ref={fileInputRef}
        type="file"
        multiple
        onChange={onPick}
        style={{ display: 'none' }}
        aria-hidden="true"
        tabIndex={-1}
      />
      {progress && (
        <p className={stylex(styles.progress)}>
          <span className={stylex(styles.progressName)}>{progress.name}</span>
          <span className={stylex(styles.progressBar)} aria-hidden="true">
            <span className={stylex(styles.progressFill)} style={{ width: `${progress.pct}%` }} />
          </span>
          <span className={stylex(styles.progressPct)}>{progress.pct}%</span>
        </p>
      )}
      {error && <p className={stylex(styles.error)} role="alert">{error}</p>}
    </div>
  );
}

const styles = stylex.create({
  zone: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '8px',
    padding: '32px 20px',
    backgroundColor: '#E9E1D0',
    border: '2px dashed #D8CDB7',
    borderRadius: '4px',
    textAlign: 'center',
    cursor: 'pointer',
    color: '#4A443C',
    minHeight: '160px',
    transitionProperty: 'background-color, border-color, box-shadow',
    transitionDuration: '120ms',
    outline: 'none',
    ':focus-visible': {
      borderColor: '#A8332A',
      boxShadow: '0 0 0 2px #A8332A33',
    },
  },
  zoneHover: {
    backgroundColor: '#D8CDB7',
    borderColor: '#A8332A',
  },
  glyph: {
    fontSize: '28px',
    color: '#A8332A',
    lineHeight: '1',
    marginBottom: '4px',
  },
  headline: {
    fontSize: '16px',
    color: '#1A1714',
    fontWeight: '500',
    margin: 0,
  },
  help: {
    fontSize: '13px',
    color: '#8C8474',
    margin: 0,
  },
  progress: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '6px',
    width: '100%',
    maxWidth: '320px',
    marginTop: '12px',
  },
  progressName: {
    fontSize: '13px',
    color: '#4A443C',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    wordBreak: 'break-all',
  },
  progressBar: {
    width: '100%',
    height: '4px',
    backgroundColor: '#D8CDB7',
    borderRadius: '2px',
    overflow: 'hidden',
  },
  progressFill: {
    display: 'block',
    height: '100%',
    backgroundColor: '#A8332A',
  },
  progressPct: {
    fontSize: '12px',
    color: '#8C8474',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  },
  error: {
    color: '#8B2A2A',
    fontSize: '14px',
    marginTop: '12px',
  },
});

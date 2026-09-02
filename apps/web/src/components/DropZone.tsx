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
  // A well pressed into the sheet. The deboss is the affordance -- this is a
  // place you put things into -- so no dashed border is needed to say so.
  zone: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '8px',
    padding: '32px 20px',
    backgroundColor: '#EDE5D5',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
    boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px #FFFBF2',
    textAlign: 'center',
    cursor: 'pointer',
    color: '#4A443C',
    minHeight: '164px',
    transitionProperty: 'background-color, border-color, box-shadow',
    transitionDuration: '140ms',
    outline: 'none',
    ':focus-visible': {
      borderColor: '#A8332A',
      boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px #FFFBF2, 0 0 0 3px rgba(168,51,42,0.30)',
    },
  },
  // Dragging over presses the well deeper.
  zoneHover: {
    backgroundColor: '#E7DDC8',
    borderColor: '#A8332A',
    boxShadow: 'inset 3px 3px 8px rgba(90,74,52,0.32), inset -2px -2px 5px #FFFBF2',
  },
  glyph: {
    fontSize: '30px',
    color: '#A8332A',
    lineHeight: '1',
    marginBottom: '2px',
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
    marginTop: '14px',
  },
  progressName: {
    fontSize: '13px',
    color: '#4A443C',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    wordBreak: 'break-all',
  },
  // Debossed track, raised fill -- the same depth grammar as the rest.
  progressBar: {
    width: '100%',
    height: '6px',
    backgroundColor: '#E3D9C4',
    borderRadius: '999px',
    overflow: 'hidden',
    boxShadow: 'inset 1px 1px 3px rgba(90,74,52,0.30)',
  },
  progressFill: {
    display: 'block',
    height: '100%',
    borderRadius: '999px',
    backgroundColor: '#A8332A',
    transitionProperty: 'width',
    transitionDuration: '160ms',
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

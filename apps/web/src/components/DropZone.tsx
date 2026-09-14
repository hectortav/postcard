import { useCallback, useRef, useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { uploadFile, UploadError } from '../lib/api';

/** One file's place in the queue. Each row keeps its own outcome and its own cancel. */
type Item = {
  key: string;
  name: string;
  size: number;
  loaded: number;
  state: 'waiting' | 'sending' | 'done' | 'failed' | 'cancelled';
  message?: string;
};

let nextKey = 0;

export function DropZone() {
  const [items, setItems] = useState<Item[]>([]);
  const [hover, setHover] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const controllers = useRef(new Map<string, AbortController>());

  const patch = useCallback((key: string, change: Partial<Item>) => {
    setItems((prev) => prev.map((it) => (it.key === key ? { ...it, ...change } : it)));
  }, []);

  const handleFiles = useCallback(
    async (files: FileList | File[]) => {
      const list = Array.from(files);
      if (list.length === 0) return;
      const queued: Item[] = list.map((f) => ({
        key: `u${nextKey++}`,
        name: f.name,
        size: f.size,
        loaded: 0,
        state: 'waiting',
      }));
      // Every file gets its own row. There used to be one shared progress slot, so dropping
      // ten files showed a single bar jumping between names, and an error from the first was
      // still on screen under a later success.
      setItems((prev) => [...prev, ...queued]);

      for (let i = 0; i < list.length; i++) {
        const file = list[i]!;
        const item = queued[i]!;
        const controller = new AbortController();
        controllers.current.set(item.key, controller);
        patch(item.key, { state: 'sending' });
        try {
          await uploadFile(file, (loaded) => patch(item.key, { loaded }), controller.signal);
          patch(item.key, { state: 'done', loaded: file.size });
        } catch (e) {
          const cancelled = e instanceof UploadError && e.kind === 'cancelled';
          patch(item.key, {
            state: cancelled ? 'cancelled' : 'failed',
            message: e instanceof Error ? e.message : String(e),
          });
        } finally {
          controllers.current.delete(item.key);
        }
      }
    },
    [patch],
  );

  const cancel = useCallback((key: string) => {
    controllers.current.get(key)?.abort();
  }, []);

  const dismiss = useCallback((key: string) => {
    setItems((prev) => prev.filter((it) => it.key !== key));
  }, []);

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

  const pct = (it: Item) => (it.size === 0 ? 100 : Math.round((it.loaded / it.size) * 100));

  return (
    <div className={stylex(styles.wrap)}>
      <div
        className={stylex(styles.zone, hover && styles.zoneHover)}
        onDragOver={(e) => {
          e.preventDefault();
          setHover(true);
        }}
        onDragLeave={() => setHover(false)}
        onDrop={onDrop}
        onClick={(e) => {
          // The file input lives inside this element, so its own click bubbles straight back
          // here. Without this guard the handler re-opens the picker on the event it just
          // caused: browsers mask that with their click-in-progress flag, but the recursion
          // is real and nothing should depend on being saved from it.
          if (e.target === fileInputRef.current) return;
          fileInputRef.current?.click();
        }}
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
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'none' }}
          aria-hidden="true"
          tabIndex={-1}
        />
      </div>
      {items.length > 0 && (
        <ul className={stylex(styles.queue)}>
          {items.map((it) => (
            <li key={it.key} className={stylex(styles.queueRow)}>
              <span className={stylex(styles.progressName)}>{it.name}</span>
              {it.state === 'sending' || it.state === 'waiting' ? (
                <>
                  <span
                    className={stylex(styles.progressBar)}
                    role="progressbar"
                    aria-label={`Sending ${it.name}`}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={pct(it)}
                  >
                    <span className={stylex(styles.progressFill)} style={{ width: `${pct(it)}%` }} />
                  </span>
                  <span className={stylex(styles.progressPct)}>{pct(it)}%</span>
                  <button
                    type="button"
                    className={stylex(styles.rowButton)}
                    onClick={() => cancel(it.key)}
                  >
                    Cancel
                  </button>
                </>
              ) : (
                <>
                  <span
                    className={stylex(styles.outcome, it.state === 'failed' && styles.outcomeBad)}
                    {...(it.state === 'failed' ? { role: 'alert' } : {})}
                  >
                    {it.state === 'done' ? 'Sent' : it.state === 'cancelled' ? 'Cancelled' : it.message}
                  </span>
                  <button
                    type="button"
                    className={stylex(styles.rowButton)}
                    onClick={() => dismiss(it.key)}
                    aria-label={`Dismiss ${it.name}`}
                  >
                    Dismiss
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const styles = stylex.create({
  wrap: { display: 'block' },
  queue: {
    listStyle: 'none',
    margin: '12px 0 0 0',
    padding: 0,
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
  },
  queueRow: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    fontSize: '12px',
    color: '#4A443C',
    flexWrap: 'wrap',
  },
  rowButton: {
    appearance: 'none',
    background: 'none',
    border: 'none',
    padding: 0,
    font: 'inherit',
    color: '#A8332A',
    textDecoration: 'underline',
    cursor: 'pointer',
    flex: '0 0 auto',
  },
  outcome: { flex: '1 1 auto', minWidth: 0 },
  outcomeBad: { color: '#A8332A' },
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

import { useEffect, useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { useWebSocket } from './hooks/useWebSocket';
import { useWakeLock } from './hooks/useWakeLock';
import { DropZone } from './components/DropZone';
import { FileList } from './components/FileList';
import { Clipboard } from './components/Clipboard';
import { QRCode } from './components/QRCode';
import type { FileEntry, SendmeMode } from './types';

const WS_URL = (): string => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;

type Tab = 'files' | 'clipboard' | 'qr';

const TABS: readonly Tab[] = ['files', 'clipboard', 'qr'] as const;

const TAB_LABEL: Record<Tab, string> = {
  files: 'Files',
  clipboard: 'Clipboard',
  qr: 'QR',
};

export function App() {
  useWakeLock();
  const [tab, setTab] = useState<Tab>('files');
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [clipboard, setClipboard] = useState<string>('');
  const [mode, setMode] = useState<SendmeMode>('lan');
  const [hotspot, setHotspot] = useState<{ ssid: string; password: string } | null>(null);

  const { events, send } = useWebSocket(WS_URL());

  // One-shot initial hydration
  useEffect(() => {
    const ac = new AbortController();
    fetch('/api/files', { cache: 'no-store', signal: ac.signal })
      .then(async (r) => {
        const m = r.headers.get('X-Sendme-Mode');
        if (m === 'hotspot' || m === 'lan') setMode(m);
        if (r.ok) setFiles(await r.json());
      })
      .catch(() => {});
    return () => ac.abort();
  }, []);

  // React to WS events (snapshot hydrates, deltas apply)
  useEffect(() => {
    const ev = events[events.length - 1];
    if (!ev) return;
    if (ev.type === 'snapshot') {
      setFiles(ev.files);
      setClipboard(ev.clipboard);
      if (ev.hotspot) setHotspot(ev.hotspot);
    } else if (ev.type === 'file_added') {
      setFiles((prev) => (prev.some((f) => f.id === ev.id) ? prev : [...prev, { id: ev.id, name: ev.name, size: ev.size, mtime: ev.mtime, sha256: ev.sha256 }]));
    } else if (ev.type === 'file_removed') {
      setFiles((prev) => prev.filter((f) => f.id !== ev.id));
    } else if (ev.type === 'clipboard') {
      setClipboard(ev.text);
    }
  }, [events]);

  return (
    <div className={stylex(styles.shell)} data-mode={mode}>
      <nav className={stylex(styles.tabs)}>
        {TABS.map((t) => (
          <button key={t} className={stylex(styles.tab, tab === t && styles.tabActive)} onClick={() => setTab(t)}>
            {TAB_LABEL[t]}
          </button>
        ))}
      </nav>
      <main className={stylex(styles.main)}>
        {tab === 'files' && (
          <>
            <DropZone />
            <FileList files={files} />
          </>
        )}
        {tab === 'clipboard' && (
          <Clipboard
            value={clipboard}
            onChange={(t) => {
              setClipboard(t);
              send({ type: 'clipboard', text: t });
            }}
          />
        )}
        {tab === 'qr' &&
          (hotspot ? (
            <QRCode mode="hotspot" ssid={hotspot.ssid} password={hotspot.password} />
          ) : (
            <QRCode mode="lan" url={location.href} />
          ))}
      </main>
    </div>
  );
}

const styles = stylex.create({
  shell: {
    minHeight: '100vh',
    backgroundColor: '#0b0d10',
    color: '#e7ecf3',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  },
  tabs: {
    display: 'flex',
    gap: '8px',
    padding: '16px',
    borderBottom: `1px solid ${'#161a20'}`,
  },
  tab: {
    background: 'transparent',
    color: '#8b95a5',
    border: 'none',
    padding: `${'8px'} ${'16px'}`,
    borderRadius: '8px',
    cursor: 'pointer',
  },
  tabActive: {
    color: '#e7ecf3',
    backgroundColor: '#161a20',
  },
  main: { padding: '24px' },
});

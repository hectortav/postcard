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
      <header className={stylex(styles.header)}>
        <div className={stylex(styles.stripe)} aria-hidden="true" />
        <h1 className={stylex(styles.title)}>sendme.</h1>
        <p className={stylex(styles.subtitle)}>a one-shot way to move a file between two devices on the same wifi</p>
      </header>
      <nav className={stylex(styles.tabs)} role="tablist">
        {TABS.map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={stylex(styles.tab, tab === t && styles.tabActive)}
            onClick={() => setTab(t)}
          >
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
      <footer className={stylex(styles.footer)}>
        <span className={stylex(styles.mode)}>
          {mode === 'hotspot' ? 'Hotspot' : 'LAN'}
        </span>
        <span className={stylex(styles.dot)} aria-hidden="true">·</span>
        <span className={stylex(styles.bind)}>{location.host}</span>
        <span className={stylex(styles.spacer)} />
        <span className={stylex(styles.signoff)}>by air</span>
      </footer>
    </div>
  );
}

const styles = stylex.create({
  shell: {
    minHeight: '100vh',
    backgroundColor: '#F4EEE2',
    color: '#1A1714',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    display: 'flex',
    flexDirection: 'column',
    WebkitFontSmoothing: 'antialiased',
    textRendering: 'optimizeLegibility',
  },
  header: {
    paddingTop: '20px',
    paddingBottom: '20px',
    paddingLeft: '24px',
    paddingRight: '24px',
    '@media (max-width: 480px)': {
      paddingTop: '16px',
      paddingBottom: '16px',
      paddingLeft: '20px',
      paddingRight: '20px',
    },
  },
  stripe: {
    height: '4px',
    marginLeft: '-24px',
    marginRight: '-24px',
    marginBottom: '20px',
    backgroundImage:
      'repeating-linear-gradient(90deg, #A8332A 0 12px, #1F3A5F 12px 24px, #A8332A 24px 36px)',
    '@media (max-width: 480px)': {
      marginLeft: '-20px',
      marginRight: '-20px',
    },
  },
  title: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontWeight: '400',
    fontSize: 'clamp(40px, 8vw, 60px)',
    lineHeight: '1',
    margin: '0 0 8px 0',
    color: '#1A1714',
    letterSpacing: '-0.01em',
  },
  subtitle: {
    fontSize: '15px',
    lineHeight: '1.45',
    color: '#4A443C',
    margin: 0,
    maxWidth: '34ch',
  },
  tabs: {
    display: 'flex',
    gap: '0',
    paddingLeft: '24px',
    paddingRight: '24px',
    borderBottom: '1px solid #D8CDB7',
    backgroundColor: '#F4EEE2',
    position: 'sticky',
    top: 0,
    zIndex: 1,
    '@media (max-width: 480px)': {
      paddingLeft: '20px',
      paddingRight: '20px',
    },
  },
  tab: {
    background: 'transparent',
    color: '#8C8474',
    border: 'none',
    borderBottom: '2px solid transparent',
    padding: '14px 16px',
    marginBottom: '-1px',
    cursor: 'pointer',
    fontSize: '15px',
    fontFamily: 'inherit',
    minHeight: '48px',
    transitionProperty: 'color, border-color',
    transitionDuration: '120ms',
    outline: 'none',
    ':focus-visible': {
      color: '#1A1714',
      borderBottomColor: '#A8332A',
    },
  },
  tabActive: {
    color: '#1A1714',
    borderBottomColor: '#A8332A',
    fontWeight: '500',
  },
  main: {
    padding: '24px',
    flex: '1 0 auto',
    maxWidth: '640px',
    width: '100%',
    marginLeft: 'auto',
    marginRight: 'auto',
    boxSizing: 'border-box',
    '@media (max-width: 480px)': {
      padding: '20px 16px',
    },
  },
  footer: {
    display: 'flex',
    gap: '8px',
    alignItems: 'center',
    padding: '14px 24px',
    borderTop: '1px solid #D8CDB7',
    color: '#8C8474',
    fontSize: '12px',
    '@media (max-width: 480px)': {
      padding: '12px 20px',
      flexWrap: 'wrap',
    },
  },
  mode: {
    color: '#A8332A',
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    fontSize: '11px',
  },
  dot: { color: '#D8CDB7' },
  bind: {
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    color: '#4A443C',
  },
  spacer: { flex: '1 1 auto' },
  signoff: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    color: '#8C8474',
  },
});

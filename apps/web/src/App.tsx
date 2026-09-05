import { useCallback, useEffect, useMemo, useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { useWebSocket } from './hooks/useWebSocket';
import { useWakeLock } from './hooks/useWakeLock';
import { DropZone } from './components/DropZone';
import { FileList } from './components/FileList';
import { PinSettings } from './components/PinSettings';
import { Clipboard } from './components/Clipboard';
import { Stamp } from './components/Stamp';
import { QRCode } from './components/QRCode';
import { PinLockScreen, type VerifyResult } from './components/PinLockScreen';
import type { FileEntry, PostcardMode } from './types';

const WS_URL = (): string => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;

type Tab = 'files' | 'clipboard' | 'qr';

const TABS: readonly Tab[] = ['files', 'clipboard', 'qr'] as const;

const TAB_LABEL: Record<Tab, string> = {
  files: 'Files',
  clipboard: 'Clipboard',
  qr: 'QR',
};

// Parse `?pin=<digits>` from the URL fragment. Returns the PIN length if
// the fragment signals that the server was started with `--pin`; null
// otherwise. The fragment is never sent to the server, so reading it is
// safe.
function readPinLengthFromHash(): number | null {
  if (typeof location === 'undefined') return null;
  const hash = location.hash.startsWith('#') ? location.hash.slice(1) : location.hash;
  if (!hash) return null;
  const params = new URLSearchParams(hash);
  if (params.has('pin')) return 4;
  return null;
}

async function verifyPinOnServer(pin: string): Promise<VerifyResult> {
  try {
    const res = await fetch('/api/pin/verify', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ pin }),
    });
    if (res.status === 200) return { ok: true };
    if (res.status === 429) {
      const body = await res.json().catch(() => ({}));
      return { ok: false, reason: 'locked', lockoutMs: Number(body.lockoutMsRemaining) || 0 };
    }
    return { ok: false, reason: 'wrong' };
  } catch {
    // Network or parse error — treat as a wrong PIN so the user can retry.
    return { ok: false, reason: 'wrong' };
  }
}

export function App() {
  useWakeLock();
  const [tab, setTab] = useState<Tab>('files');
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [clipboard, setClipboard] = useState<string>('');
  const [mode, setMode] = useState<PostcardMode>('lan');
  const [hotspot, setHotspot] = useState<{ ssid: string; password: string } | null>(null);
  const pinLength = useMemo<number | null>(() => readPinLengthFromHash(), []);
  const [pinUnlocked, setPinUnlocked] = useState<boolean>(() => readPinLengthFromHash() === null);
  // The PIN settings rewrite location.hash (key/pin for QR sharing); track it so
  // the QR tab re-renders with the fresh URL. Hash assignment fires hashchange
  // natively, so no manual event is needed.
  const [href, setHref] = useState<string>(() => location.href);
  useEffect(() => {
    const onHash = () => setHref(location.href);
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const { events, send } = useWebSocket(WS_URL());

  const handlePinVerified = useCallback((_pin: string) => {
    setPinUnlocked(true);
  }, []);

  // One-shot initial hydration
  useEffect(() => {
    const ac = new AbortController();
    fetch('/api/files', { cache: 'no-store', signal: ac.signal })
      .then(async (r) => {
        const m = r.headers.get('X-Postcard-Mode');
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
      {pinLength !== null && !pinUnlocked ? (
        <PinLockScreen
          pinLength={pinLength}
          verify={verifyPinOnServer}
          onVerified={handlePinVerified}
        />
      ) : (
        <div className={stylex(styles.card)}>
          <header className={stylex(styles.masthead)}>
            <div className={stylex(styles.mastheadText)}>
              <h1 className={stylex(styles.title)}>postcard.</h1>
              <p className={stylex(styles.subtitle)}>
                a one-shot way to move a file between two devices on the same wifi
              </p>
            </div>
            <Stamp size={44} hole="#F4EEE2" />
          </header>
          <nav className={stylex(styles.tabs)} role="tablist">
            {TABS.map((t) => (
              <div
                key={t}
                role="tab"
                tabIndex={0}
                aria-selected={tab === t}
                className={stylex(styles.tab, tab === t && styles.tabActive)}
                onClick={() => setTab(t)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    setTab(t);
                  }
                }}
              >
                {TAB_LABEL[t]}
              </div>
            ))}
          </nav>
          <main className={stylex(styles.main)}>
            {tab === 'files' && (
              <>
                <DropZone />
                <FileList files={files} />
                <PinSettings />
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
                <QRCode mode="lan" url={href} />
              ))}
          </main>
          <footer className={stylex(styles.footer)}>
            <span className={stylex(styles.mode)}>
              {mode === 'hotspot' ? 'Hotspot' : 'LAN'}
            </span>
            <span className={stylex(styles.bind)}>{location.host}</span>
            <span className={stylex(styles.spacer)} />
            <span className={stylex(styles.signoff)}>by air</span>
          </footer>
        </div>
      )}
    </div>
  );
}

const styles = stylex.create({
  // The desk. Darker than the card so the sheet has something to rest on --
  // this separation is what the whole letterpress system depends on.
  shell: {
    minHeight: '100vh',
    backgroundColor: '#E3D9C4',
    color: '#1A1714',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'flex-start',
    padding: '32px 24px',
    boxSizing: 'border-box',
    WebkitFontSmoothing: 'antialiased',
    textRendering: 'optimizeLegibility',
    '@media (max-width: 560px)': { padding: '0' },
  },
  // The sheet.
  card: {
    width: '100%',
    maxWidth: '600px',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#F4EEE2',
    border: '1px solid #D8CDB7',
    borderRadius: '12px',
    boxShadow: '0 1px 1px rgba(90,74,52,0.10), 0 10px 28px -10px rgba(90,74,52,0.40)',
    overflow: 'hidden',
    '@media (max-width: 560px)': {
      minHeight: '100vh',
      borderRadius: '0',
      border: 'none',
      boxShadow: 'none',
    },
  },
  masthead: {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: '20px',
    padding: '24px 24px 18px',
    '@media (max-width: 480px)': { padding: '20px 20px 16px' },
  },
  mastheadText: { minWidth: 0 },
  title: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontWeight: '400',
    fontSize: 'clamp(34px, 7vw, 46px)',
    lineHeight: '1',
    margin: '0 0 8px 0',
    color: '#1A1714',
    letterSpacing: '-0.015em',
  },
  subtitle: {
    fontSize: '14px',
    lineHeight: '1.5',
    color: '#4A443C',
    margin: 0,
    maxWidth: '38ch',
  },
  // Index-card tabs: the active one joins the sheet below it, so the divider
  // breaks under it rather than the tab wearing a coloured underline.
  tabs: {
    display: 'flex',
    gap: '4px',
    padding: '0 24px',
    // The strip is a shade darker than the sheet, which is the only way the
    // active tab -- which IS the sheet colour -- can read as joined to it.
    backgroundColor: '#EBE2D0',
    borderBottom: '1px solid #D8CDB7',
    '@media (max-width: 480px)': { padding: '0 16px' },
  },
  tab: {
    position: 'relative',
    bottom: '-1px',
    background: 'transparent',
    color: '#8C8474',
    border: '1px solid transparent',
    borderTopLeftRadius: '8px',
    borderTopRightRadius: '8px',
    padding: '12px 18px',
    cursor: 'pointer',
    fontSize: '14px',
    fontFamily: 'inherit',
    minHeight: '44px',
    transitionProperty: 'color, background-color',
    transitionDuration: '120ms',
    outline: 'none',
    userSelect: 'none',
    ':hover': { color: '#1A1714', backgroundColor: '#F0E8D9' },
    ':focus-visible': { color: '#1A1714', boxShadow: '0 0 0 2px #A8332A' },
  },
  tabActive: {
    color: '#1A1714',
    fontWeight: '500',
    backgroundColor: '#F4EEE2',
    borderColor: '#D8CDB7',
    borderBottomColor: '#F4EEE2',
    ':hover': { backgroundColor: '#F4EEE2' },
  },
  main: {
    padding: '24px',
    flex: '1 0 auto',
    width: '100%',
    boxSizing: 'border-box',
    '@media (max-width: 480px)': { padding: '20px 16px' },
  },
  footer: {
    display: 'flex',
    gap: '10px',
    alignItems: 'center',
    padding: '12px 24px',
    borderTop: '1px solid #D8CDB7',
    backgroundColor: '#EFE7D8',
    color: '#8C8474',
    fontSize: '12px',
    '@media (max-width: 480px)': { padding: '12px 16px', flexWrap: 'wrap' },
  },
  // A pressed-in chip, not a shouty uppercase label.
  mode: {
    color: '#A8332A',
    fontWeight: '600',
    fontSize: '11px',
    padding: '3px 8px',
    borderRadius: '999px',
    backgroundColor: '#F4EEE2',
    boxShadow: 'inset 1px 1px 3px rgba(90,74,52,0.22), inset -1px -1px 2px #FFFBF2',
  },
  bind: {
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    color: '#4A443C',
    minWidth: 0,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  spacer: { flex: '1 1 auto' },
  signoff: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    color: '#8C8474',
  },
});

import { useEffect, useRef } from 'preact/hooks';
// The `qrcode` npm package (v1.5.4) ships no .d.ts file. The @ts-expect-error
// is the smallest possible workaround; the runtime API is the documented one
// (see node_modules/qrcode/lib/browser.js).
// @ts-expect-error -- untyped npm module
import QR from 'qrcode';
import stylex from '@stylexjs/stylex';
import { tokens } from '../tokens.stylex';

type Props =
  | { mode: 'lan'; url: string }
  | { mode: 'hotspot'; ssid: string; password: string };

export function QRCode(props: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const text = props.mode === 'hotspot'
    ? `WIFI:T:WPA;S:${props.ssid};P:${props.password};;`
    : props.url;

  useEffect(() => {
    if (!ref.current) return;
    QR.toString(text, { type: 'svg', margin: 1, color: { dark: tokens.colors.ink, light: tokens.colors.paper } })
      .then((svg: string) => { if (ref.current) ref.current.innerHTML = svg; })
      .catch(() => {});
  }, [text]);

  const heading = props.mode === 'hotspot' ? 'Scan to join the hotspot' : 'Scan to open on another device';
  const sub = props.mode === 'hotspot'
    ? `Network: ${props.ssid}`
    : 'Open the camera app and point it at the square.';

  return (
    <div className={stylex(styles.wrap)}>
      <p className={stylex(styles.heading)}>{heading}</p>
      <div className={stylex(styles.card)}>
        <div ref={ref} className={stylex(styles.qr)} />
      </div>
      <p className={stylex(styles.sub)}>{sub}</p>
      <p className={stylex(styles.text)}>{text}</p>
    </div>
  );
}

const styles = stylex.create({
  wrap: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '14px',
    textAlign: 'center',
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontSize: '20px',
    color: '#1A1714',
    margin: 0,
  },
  // The code itself is a physical tile resting on the sheet -- the one place
  // in this view where something is raised rather than pressed in.
  card: {
    padding: '18px',
    backgroundColor: '#FFFBF2',
    border: '1px solid #D8CDB7',
    borderRadius: '12px',
    boxShadow: '2px 2px 5px rgba(90,74,52,0.24), -2px -2px 4px #FFFBF2',
    lineHeight: 0,
  },
  qr: { width: '208px', height: '208px', display: 'block' },
  sub: { fontSize: '13px', color: '#8C8474', margin: 0 },
  // The URL is the thing you read character by character, so it stays mono
  // and selectable. Losing the address bar in the app window makes this the
  // only place the URL is legible -- see the app-window design note.
  text: {
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '13px',
    color: '#4A443C',
    margin: 0,
    wordBreak: 'break-all',
    userSelect: 'all',
    maxWidth: '34ch',
  },
});

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
    gap: '12px',
    textAlign: 'center',
  },
  heading: {
    fontSize: '15px',
    color: '#1A1714',
    fontWeight: '500',
    margin: 0,
  },
  card: {
    backgroundColor: '#F4EEE2',
    padding: '16px',
    borderRadius: '4px',
    border: '1px solid #D8CDB7',
  },
  qr: {
    width: 'min(72vw, 280px)',
    aspectRatio: '1',
  },
  sub: {
    fontSize: '13px',
    color: '#4A443C',
    margin: '4px 0 0 0',
  },
  text: {
    color: '#8C8474',
    fontSize: '12px',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    wordBreak: 'break-all',
    margin: 0,
    maxWidth: '320px',
  },
});

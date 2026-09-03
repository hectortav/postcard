import { useEffect, useRef } from 'preact/hooks';
// The `qrcode` npm package (v1.5.4) ships no .d.ts file. The @ts-expect-error
// is the smallest possible workaround; the runtime API is the documented one
// (see node_modules/qrcode/lib/browser.js).
// @ts-expect-error -- untyped npm module
import QR from 'qrcode';
import stylex from '@stylexjs/stylex';

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
    QR.toString(text, { type: 'svg', margin: 1, color: { dark: '#e7ecf3', light: '#0b0d10' } })
      .then((svg: string) => { if (ref.current) ref.current.innerHTML = svg; })
      .catch(() => {});
  }, [text]);

  return (
    <div className={stylex(styles.wrap)}>
      <div ref={ref} className={stylex(styles.qr)} />
      <p className={stylex(styles.text)}>{text}</p>
    </div>
  );
}

const styles = stylex.create({
  wrap: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px' },
  qr: { width: 'min(80vw, 320px)', aspectRatio: '1' },
  text: { color: '#8b95a5', fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace', wordBreak: 'break-all', textAlign: 'center' },
});

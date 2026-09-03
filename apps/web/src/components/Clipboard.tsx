import { useEffect, useRef } from 'preact/hooks';
import stylex from '@stylexjs/stylex';

export function Clipboard({ value, onChange }: { value: string; onChange: (t: string) => void }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const lastSent = useRef<string>(value);

  useEffect(() => { if (ref.current && ref.current.value !== value) ref.current.value = value; }, [value]);

  function onInput(ev: Event) {
    const t = (ev.target as HTMLTextAreaElement).value;
    if (t === lastSent.current) return;
    lastSent.current = t;
    onChange(t);
  }

  return <textarea ref={ref} className={stylex(styles.area)} onInput={onInput} placeholder="Shared clipboard" />;
}

const styles = stylex.create({
  area: { width: '100%', minHeight: '40vh', padding: '16px', backgroundColor: '#161a20', color: '#e7ecf3', border: 'none', borderRadius: '8px', fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace', fontSize: '14px' },
});

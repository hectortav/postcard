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

  return (
    <div className={stylex(styles.wrap)}>
      <label className={stylex(styles.label)} htmlFor="sendme-clipboard">Shared clipboard</label>
      <p className={stylex(styles.help)}>Anything you type here shows up on the other devices.</p>
      <textarea
        id="sendme-clipboard"
        ref={ref}
        className={stylex(styles.area)}
        onInput={onInput}
        placeholder="Paste a link, a phone number, a line of code..."
        spellcheck={true}
        autoComplete="off"
        autoCorrect="on"
        autoCapitalize="sentences"
      />
    </div>
  );
}

const styles = stylex.create({
  wrap: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
  },
  label: {
    fontSize: '13px',
    color: '#1A1714',
    fontWeight: '500',
  },
  help: {
    fontSize: '13px',
    color: '#8C8474',
    margin: 0,
  },
  area: {
    width: '100%',
    minHeight: '40vh',
    padding: '16px',
    backgroundColor: '#E9E1D0',
    color: '#1A1714',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '15px',
    lineHeight: '1.5',
    boxSizing: 'border-box',
    resize: 'vertical',
    marginTop: '8px',
    outline: 'none',
    ':focus': {
      borderColor: '#A8332A',
      boxShadow: '0 0 0 2px #A8332A33',
    },
  },
});

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
      <label className={stylex(styles.label)} htmlFor="postcard-clipboard">Shared clipboard</label>
      <p className={stylex(styles.help)}>Anything you type here shows up on the other devices.</p>
      <textarea
        id="postcard-clipboard"
        ref={ref}
        className={stylex(styles.area)}
        onInput={onInput}
        placeholder="Paste a link, a phone number, a line of code..."
        autoComplete="off"
        autoCorrect="on"
        autoCapitalize="sentences"
      />
    </div>
  );
}

const styles = stylex.create({
  wrap: { display: 'flex', flexDirection: 'column', gap: '8px' },
  label: {
    fontSize: '14px',
    fontWeight: '500',
    color: '#1A1714',
  },
  // Text goes into the sheet, so the field is pressed in.
  area: {
    width: '100%',
    minHeight: '200px',
    boxSizing: 'border-box',
    padding: '14px 16px',
    backgroundColor: '#EDE5D5',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
    boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px #FFFBF2',
    color: '#1A1714',
    fontSize: '15px',
    lineHeight: '1.55',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    resize: 'vertical',
    outline: 'none',
    transitionProperty: 'border-color, box-shadow',
    transitionDuration: '140ms',
    '::placeholder': { color: '#8C8474' },
    ':focus': {
      borderColor: '#A8332A',
      boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px #FFFBF2, 0 0 0 3px rgba(168,51,42,0.28)',
    },
  },
  help: { fontSize: '12px', color: '#8C8474', margin: 0 },
});

import { useEffect, useRef, useState, useCallback } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { Stamp } from './Stamp';

// The shake animation is a single, component-local keyframe rule. The
// StyleX 0.17.5 babel plugin restricts `stylex.create` style values to
// primitives (no function/object values), so we inject a tiny <style>
// element scoped to this module. happy-dom supports inline <style> blocks
// for the tests; the Vite build inlines it into the production HTML.
const SHAKE_CSS = `@keyframes postcard-pin-shake {
  0%   { transform: translateX(0); }
  20%  { transform: translateX(-6px); }
  40%  { transform: translateX(6px); }
  60%  { transform: translateX(-4px); }
  80%  { transform: translateX(4px); }
  100% { transform: translateX(0); }
}`;

export type VerifyResult =
  | { ok: true }
  | { ok: false; reason: 'wrong' }
  | { ok: false; reason: 'locked'; lockoutMs: number };

type Props = {
  pinLength: number;
  onVerified: (pin: string) => void;
  verify: (pin: string) => Promise<VerifyResult>;
};

/**
 * Modal card that gates the dashboard while a 4-digit PIN is required.
 *
 * The URL fragment is expected to contain `&pin=<digits>` whenever the
 * host started the server with `--pin`. The component is mounted by
 * {@link App} and shows instead of the file list / clipboard / QR code
 * until the user enters the correct PIN.
 *
 * Three consecutive wrong PINs (server-side) trigger a 429 lockout. The
 * server returns the lockout duration in the body; we tick a local
 * countdown and disable the inputs until it expires.
 */
export function PinLockScreen({ pinLength, onVerified, verify }: Props) {
  const [digits, setDigits] = useState<string[]>(() => Array.from({ length: pinLength }, () => ''));
  const [shake, setShake] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lockoutMs, setLockoutMs] = useState(0);
  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);

  // Auto-advance focus to the first empty box whenever digits change.
  useEffect(() => {
    const idx = digits.findIndex((d) => d === '');
    if (idx < 0) return;
    inputRefs.current[idx]?.focus();
  }, [digits]);

  // Lockout countdown ticker. Re-renders every 250ms while the lockout is active.
  useEffect(() => {
    if (lockoutMs <= 0) return;
    const handle = setInterval(() => {
      setLockoutMs((ms) => {
        const next = Math.max(0, ms - 250);
        if (next === 0) {
          setError(null);
          setDigits((prev) => prev.map(() => ''));
        }
        return next;
      });
    }, 250);
    return () => clearInterval(handle);
  }, [lockoutMs]);

  const submit = useCallback(
    async (final: string[]) => {
      const pin = final.join('');
      if (pin.length !== pinLength) return;
      setBusy(true);
      setError(null);
      const result = await verify(pin);
      if (result.ok) {
        setBusy(false);
        onVerified(pin);
        return;
      }
      if (result.reason === 'locked') {
        setBusy(false);
        setLockoutMs(result.lockoutMs);
        setError('Too many wrong attempts. Try again in ' + formatMs(result.lockoutMs) + '.');
        return;
      }
      // Wrong PIN: clear the boxes, trigger the shake animation, refocus box 0.
      setBusy(false);
      setDigits((prev) => prev.map(() => ''));
      setShake((n) => n + 1);
      setError('That PIN did not match. Try again.');
    },
    [pinLength, verify, onVerified],
  );

  function onInput(i: number, ev: Event) {
    const raw = (ev.target as HTMLInputElement).value;
    // Allow pasting a full PIN into any box: distribute the digits.
    if (raw.length > 1) {
      const chars = raw.replace(/\D/g, '').split('').slice(0, pinLength - i);
      if (chars.length === 0) return;
      setDigits((prev) => {
        const next = prev.slice();
        for (let j = 0; j < chars.length; j++) next[i + j] = chars[j] ?? '';
        return next;
      });
      const lastIdx = Math.min(pinLength - 1, i + chars.length - 1);
      if (lastIdx === pinLength - 1) {
        const filled = digits.slice();
        for (let j = 0; j < chars.length && i + j < pinLength; j++) filled[i + j] = chars[j] ?? '';
        void submit(filled);
      }
      return;
    }
    const ch = raw.replace(/\D/g, '').slice(-1);
    if (!ch) {
      // Re-render so the controlled input snaps back to '' (we typed
      // something non-numeric; the DOM should reflect state, not the key).
      setDigits((prev) => prev.slice());
      return;
    }
    setDigits((prev) => {
      const next = prev.slice();
      next[i] = ch;
      if (i === pinLength - 1) void submit(next);
      return next;
    });
  }

  function onKeyDown(i: number, ev: KeyboardEvent) {
    if (ev.key === 'Backspace') {
      if (digits[i] !== '') return; // let the input clear itself on next tick
      if (i > 0) {
        ev.preventDefault();
        setDigits((prev) => {
          const next = prev.slice();
          next[i - 1] = '';
          return next;
        });
        inputRefs.current[i - 1]?.focus();
      }
    } else if (ev.key === 'ArrowLeft' && i > 0) {
      ev.preventDefault();
      inputRefs.current[i - 1]?.focus();
    } else if (ev.key === 'ArrowRight' && i < pinLength - 1) {
      ev.preventDefault();
      inputRefs.current[i + 1]?.focus();
    }
  }

  const locked = lockoutMs > 0;

  return (
    <div className={stylex(styles.shell)} role="dialog" aria-modal="true" aria-labelledby="pin-heading">
      <div className={stylex(styles.watermark)} aria-hidden="true">
        <Stamp size={440} hole="#E3D9C4" title="" />
      </div>
      <style dangerouslySetInnerHTML={{ __html: SHAKE_CSS }} />
      <div className={stylex(styles.card, shake > 0 && styles.cardShake)} key={shake} data-shake={shake}>
        <div className={stylex(styles.stripe)} aria-hidden="true" />
        <h2 id="pin-heading" className={stylex(styles.heading)}>
          Enter the {pinLength}-digit PIN shown on the other device
        </h2>
        <p className={stylex(styles.sub)}>Shown once on the host&apos;s terminal</p>
        <div className={stylex(styles.boxes)}>
          {digits.map((d, i) => (
            <input
              key={i}
              ref={(el) => { inputRefs.current[i] = el; }}
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={pinLength}
              value={d}
              disabled={locked || busy}
              aria-label={`Digit ${i + 1} of ${pinLength}`}
              data-testid={`pin-box-${i}`}
              className={stylex(styles.box, locked && styles.boxDisabled, busy && styles.boxBusy)}
              onInput={(e) => onInput(i, e)}
              onKeyDown={(e) => onKeyDown(i, e)}
              onPaste={(e) => {
                e.preventDefault();
                const text = e.clipboardData?.getData('text') ?? '';
                if (!text) return;
                onInput(i, { target: { value: text } } as unknown as Event);
              }}
            />
          ))}
        </div>
        {error && (
          <p className={stylex(styles.error, locked && styles.errorLockout)} role="alert">
            {locked ? `Locked. ${formatMs(lockoutMs)} remaining.` : error}
          </p>
        )}
      </div>
    </div>
  );
}

function formatMs(ms: number): string {
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  if (m === 0) return `${s}s`;
  return `${m}m ${s.toString().padStart(2, '0')}s`;
}

const styles = stylex.create({
  // The desk, with a faint stamp watermark. The watermark exists so the frosted
  // panel has something to refract -- backdrop-filter over a flat colour is
  // invisible, which is how "glass" usually ends up as decoration.
  shell: {
    position: 'relative',
    minHeight: '100vh',
    width: '100%',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '24px',
    boxSizing: 'border-box',
    backgroundColor: '#E3D9C4',
    overflow: 'hidden',
  },
  watermark: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%) rotate(-8deg)',
    opacity: '0.18',
    pointerEvents: 'none',
    lineHeight: 0,
  },
  // The one glass surface in the product.
  card: {
    position: 'relative',
    width: '100%',
    maxWidth: '380px',
    padding: '30px 26px 26px',
    boxSizing: 'border-box',
    borderRadius: '16px',
    backgroundColor: 'rgba(244,238,226,0.72)',
    backdropFilter: 'blur(18px) saturate(1.25)',
    WebkitBackdropFilter: 'blur(18px) saturate(1.25)',
    border: '1px solid rgba(255,251,242,0.65)',
    boxShadow: '0 8px 32px -8px rgba(26,23,20,0.30)',
    overflow: 'hidden',
    textAlign: 'center',
  },
  cardShake: {
    animationName: stylex.keyframes({
      '0%, 100%': { transform: 'translateX(0)' },
      '20%': { transform: 'translateX(-7px)' },
      '40%': { transform: 'translateX(6px)' },
      '60%': { transform: 'translateX(-4px)' },
      '80%': { transform: 'translateX(2px)' },
    }),
    animationDuration: '320ms',
    animationTimingFunction: 'ease-in-out',
    '@media (prefers-reduced-motion: reduce)': { animationName: 'none' },
  },
  stripe: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: '5px',
    backgroundImage:
      'repeating-linear-gradient(135deg, #A8332A 0 10px, #1A1714 10px 20px)',
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontSize: '22px',
    fontWeight: '400',
    color: '#1A1714',
    margin: '0 0 6px 0',
  },
  sub: { fontSize: '13px', color: '#4A443C', margin: '0 0 22px 0' },
  boxes: { display: 'flex', gap: '10px', justifyContent: 'center' },
  // Digits are pressed into the sheet.
  box: {
    width: '54px',
    height: '64px',
    textAlign: 'center',
    fontSize: '26px',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    color: '#1A1714',
    backgroundColor: 'rgba(237,229,213,0.85)',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
    boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px rgba(255,251,242,0.9)',
    outline: 'none',
    transitionProperty: 'border-color, box-shadow, opacity',
    transitionDuration: '140ms',
    ':focus': {
      borderColor: '#A8332A',
      boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px rgba(255,251,242,0.9), 0 0 0 3px rgba(168,51,42,0.30)',
    },
  },
  boxBusy: { opacity: '0.55' },
  boxDisabled: { opacity: '0.4', cursor: 'not-allowed' },
  error: { marginTop: '18px', marginBottom: 0, fontSize: '13px', color: '#8B2A2A' },
  errorLockout: { color: '#1A1714', fontWeight: '500' },
});

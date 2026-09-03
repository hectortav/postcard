import { useEffect, useRef, useState, useCallback } from 'preact/hooks';
import stylex from '@stylexjs/stylex';

// The shake animation is a single, component-local keyframe rule. The
// StyleX 0.17.5 babel plugin restricts `stylex.create` style values to
// primitives (no function/object values), so we inject a tiny <style>
// element scoped to this module. happy-dom supports inline <style> blocks
// for the tests; the Vite build inlines it into the production HTML.
const SHAKE_CSS = `@keyframes sendme-pin-shake {
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
  const [digits, setDigits] = useState<string[]>(() => new Array(pinLength).fill(''));
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
  shell: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '24px',
    backgroundColor: '#F4EEE2',
    boxSizing: 'border-box',
  },
  card: {
    width: '100%',
    maxWidth: '360px',
    backgroundColor: '#E9E1D0',
    borderRadius: '4px',
    padding: '24px 20px 20px 20px',
    boxShadow: '0 2px 12px rgba(26, 23, 20, 0.08)',
    textAlign: 'center',
    boxSizing: 'border-box',
  },
  cardShake: {
    animationName: 'sendme-pin-shake',
    animationDuration: '300ms',
    animationTimingFunction: 'ease-in-out',
  },
  stripe: {
    height: '4px',
    marginLeft: '-20px',
    marginRight: '-20px',
    marginTop: '-24px',
    marginBottom: '16px',
    backgroundImage:
      'repeating-linear-gradient(90deg, #A8332A 0 12px, #1F3A5F 12px 24px, #A8332A 24px 36px)',
  },
  heading: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    fontSize: '15px',
    fontWeight: '500',
    color: '#1A1714',
    margin: '0 0 4px 0',
    lineHeight: '1.35',
  },
  sub: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    fontSize: '12px',
    color: '#8C8474',
    margin: '0 0 20px 0',
  },
  boxes: {
    display: 'flex',
    justifyContent: 'center',
    gap: '8px',
    marginBottom: '12px',
  },
  box: {
    width: '44px',
    height: '52px',
    borderRadius: '4px',
    border: '1px solid #D8CDB7',
    backgroundColor: '#F4EEE2',
    color: '#1A1714',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '22px',
    textAlign: 'center',
    padding: '0',
    outline: 'none',
    transitionProperty: ['borderColor', 'boxShadow'],
    transitionDuration: '120ms',
    ':focus': {
      borderColor: '#A8332A',
      boxShadow: '0 0 0 2px #A8332A33',
    },
  },
  boxDisabled: {
    backgroundColor: '#D8CDB7',
    color: '#8C8474',
    cursor: 'not-allowed',
  },
  boxBusy: {
    opacity: '0.6',
  },
  error: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    fontSize: '13px',
    color: '#8B2A2A',
    margin: '8px 0 0 0',
  },
  errorLockout: {
    color: '#A8332A',
    fontWeight: '500',
  },
});

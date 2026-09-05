import { useCallback, useEffect, useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';

type Config = { pinRequired: boolean; manageable: boolean };

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; config: Config }
  | { status: 'error'; message: string };

async function readConfig(): Promise<Config> {
  const res = await fetch('/api/pin/config', { cache: 'no-store' });
  if (!res.ok) throw new Error(`config ${res.status}`);
  const body = await res.json();
  return { pinRequired: body.pinRequired === true, manageable: body.manageable === true };
}

// Rewrite the URL fragment from the configure response so the QR tab keeps
// sharing a working URL: the key the server returns plus the PIN just set
// (or neither, when disabling down to a secret-less session). Assigning
// location.hash fires hashchange, which the QR tab listens for. Other
// fragment params are preserved untouched.
function writeFragment(key: string | null, pin: string | null): void {
  const raw = location.hash.startsWith('#') ? location.hash.slice(1) : location.hash;
  const params = new URLSearchParams(raw);
  if (key) params.set('key', key);
  else params.delete('key');
  if (pin) params.set('pin', pin);
  else params.delete('pin');
  location.hash = params.toString();
}

export function PinSettings() {
  const [load, setLoad] = useState<LoadState>({ status: 'loading' });
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoad({ status: 'loading' });
    try {
      setLoad({ status: 'ready', config: await readConfig() });
    } catch {
      setLoad({ status: 'error', message: 'Could not reach the server.' });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const submit = useCallback(
    async (nextPin: string | null) => {
      setBusy(true);
      setNotice(null);
      try {
        const res = await fetch('/api/pin/configure', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(nextPin === null ? {} : { pin: nextPin }),
        });
        const body = await res.json().catch(() => ({}));
        if (res.status === 403) {
          setNotice('PIN can only be changed from the computer running postcard.');
          return;
        }
        if (res.status === 400) {
          setNotice('That PIN will not work — use exactly 4 digits.');
          return;
        }
        if (!res.ok) {
          setNotice('Could not reach the server.');
          return;
        }
        writeFragment(
          typeof body.key === 'string' ? body.key : null,
          nextPin,
        );
        setPin('');
        await refresh();
      } catch {
        setNotice('Could not reach the server.');
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  if (load.status === 'loading') return null;
  if (load.status === 'error') {
    return (
      <section aria-label="PIN protection" className={stylex(styles.section)}>
        <p className={stylex(styles.help)}>
          {load.message}{' '}
          <button type="button" className={stylex(styles.link)} onClick={() => void refresh()}>
            Retry
          </button>
        </p>
      </section>
    );
  }
  // Receivers (phones) and non-owner browsers never see management.
  if (!load.config.manageable) return null;

  const on = load.config.pinRequired;
  const valid = /^[0-9]{4}$/.test(pin);

  return (
    <section aria-label="PIN protection" className={stylex(styles.section)}>
      <h2 className={stylex(styles.heading)}>PIN protection</h2>
      <p className={stylex(styles.status)}>
        {on ? 'On — new devices must type the PIN.' : 'Off — anyone on your wifi can open the dashboard.'}
      </p>
      <div className={stylex(styles.row)}>
        <input
          aria-label="New 4-digit PIN"
          className={stylex(styles.input)}
          inputMode="numeric"
          autoComplete="off"
          maxLength={4}
          placeholder="1234"
          value={pin}
          disabled={busy}
          onInput={(e) => setPin((e.target as HTMLInputElement).value.replace(/[^0-9]/g, '').slice(0, 4))}
        />
        <button
          type="button"
          className={stylex(styles.button)}
          disabled={busy || !valid}
          onClick={() => void submit(pin)}
        >
          {on ? 'Change' : 'Enable'}
        </button>
        {on && (
          <button
            type="button"
            className={stylex(styles.button, styles.buttonQuiet)}
            disabled={busy}
            onClick={() => void submit(null)}
          >
            Disable
          </button>
        )}
      </div>
      {notice && (
        <p role="alert" className={stylex(styles.notice)}>
          {notice}
        </p>
      )}
    </section>
  );
}

const styles = stylex.create({
  section: {
    marginTop: '20px',
    padding: '16px 18px',
    backgroundColor: '#EFE7D8',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontSize: '18px',
    fontWeight: '400',
    color: '#1A1714',
    margin: '0 0 6px 0',
  },
  status: { fontSize: '13px', color: '#4A443C', margin: '0 0 12px 0' },
  help: { fontSize: '13px', color: '#8C8474', margin: 0 },
  row: { display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' },
  input: {
    width: '84px',
    fontSize: '16px',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    letterSpacing: '4px',
    textAlign: 'center',
    padding: '8px 6px 8px 10px',
    color: '#1A1714',
    backgroundColor: '#F4EEE2',
    border: '1px solid #D8CDB7',
    borderRadius: '8px',
    outline: 'none',
    ':focus': { borderColor: '#A8332A' },
  },
  button: {
    fontSize: '14px',
    fontWeight: '500',
    fontFamily: 'inherit',
    color: '#F4EEE2',
    backgroundColor: '#A8332A',
    border: '1px solid #8B2A22',
    borderRadius: '8px',
    padding: '9px 16px',
    cursor: 'pointer',
    ':disabled': { opacity: '0.45', cursor: 'not-allowed' },
  },
  buttonQuiet: {
    color: '#1A1714',
    backgroundColor: 'transparent',
    borderColor: '#D8CDB7',
  },
  link: {
    background: 'none',
    border: 'none',
    padding: 0,
    color: '#A8332A',
    fontSize: 'inherit',
    cursor: 'pointer',
    textDecoration: 'underline',
  },
  notice: { fontSize: '13px', color: '#A8332A', margin: '10px 0 0 0' },
});

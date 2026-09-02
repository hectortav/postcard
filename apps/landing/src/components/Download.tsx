import { useEffect, useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';
import { detectOs, type OsId } from '../lib/detectOs';
import { fetchLatestRelease, type Release, type ReleaseAsset } from '../lib/fetchRelease';

type Status = 'loading' | 'ready' | 'empty' | 'error';

const OS_LABEL: Record<OsId, string> = {
  mac: 'macOS',
  win: 'Windows',
  linux: 'Linux',
};

const RELEASES_PAGE = 'https://github.com/hectortav/postcard/releases';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function Download() {
  const [status, setStatus] = useState<Status>('loading');
  const [release, setRelease] = useState<Release | null>(null);
  const [os, setOs] = useState<OsId>('linux');

  useEffect(() => {
    setOs(detectOs(typeof navigator !== 'undefined' ? navigator.userAgent : ''));
    let cancelled = false;
    fetchLatestRelease()
      .then((r) => {
        if (cancelled) return;
        if (!r || r.assets.length === 0) {
          setStatus('empty');
          return;
        }
        setRelease(r);
        setStatus('ready');
      })
      .catch(() => {
        if (cancelled) return;
        setStatus('error');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const byOs: Record<OsId, ReleaseAsset | undefined> = {
    mac: release?.assets.find((a) => a.os === 'mac'),
    win: release?.assets.find((a) => a.os === 'win'),
    linux: release?.assets.find((a) => a.os === 'linux'),
  };

  return (
    <section className={stylex(styles.section)} aria-labelledby="download-heading">
      <h2 id="download-heading" className={stylex(styles.heading)}>Get it</h2>
      {status === 'loading' && (
        <p className={stylex(styles.help)}>Checking for the latest release…</p>
      )}
      {(status === 'empty' || status === 'error') && (
        <div className={stylex(styles.comingSoon)}>
          <p className={stylex(styles.comingSoonText)}>
            Coming soon — see{' '}
            <a className={stylex(styles.link)} href={RELEASES_PAGE} target="_blank" rel="noreferrer">
              GitHub releases
            </a>{' '}
            for builds.
          </p>
        </div>
      )}
      {status === 'ready' && release && (
        <div className={stylex(styles.buttons)}>
          {(['mac', 'win', 'linux'] as const).map((id) => {
            const asset = byOs[id];
            const active = id === os;
            return (
              <a
                key={id}
                className={stylex(styles.button, active && styles.buttonActive, !asset && styles.buttonDisabled)}
                href={asset?.url ?? RELEASES_PAGE}
                target={asset ? '_blank' : undefined}
                rel={asset ? 'noreferrer' : undefined}
                aria-label={
                  asset
                    ? `Download ${OS_LABEL[id]} (${asset.name}, ${formatSize(asset.size)})`
                    : `${OS_LABEL[id]} build not in this release`
                }
                aria-disabled={!asset}
              >
                <span className={stylex(styles.osLabel)}>{OS_LABEL[id]}</span>
                <span className={stylex(styles.sizeLabel)}>
                  {asset ? `${asset.name} · ${formatSize(asset.size)}` : 'not in this release'}
                </span>
              </a>
            );
          })}
        </div>
      )}
    </section>
  );
}

const styles = stylex.create({
  section: {
    padding: '44px 32px',
    borderBottom: '1px solid #D8CDB7',
    '@media (max-width: 640px)': { padding: '32px 20px' },
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontSize: '26px',
    fontWeight: '400',
    color: '#1A1714',
    margin: '0 0 20px 0',
  },
  buttons: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '12px',
    marginBottom: '16px',
  },
  // Raised out of the sheet: the one thing on the page you are meant to press.
  button: {
    display: 'inline-flex',
    flexDirection: 'column',
    alignItems: 'flex-start',
    gap: '2px',
    padding: '12px 20px',
    minHeight: '56px',
    justifyContent: 'center',
    backgroundColor: '#F4EEE2',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
    boxShadow: '2px 2px 5px rgba(90,74,52,0.24), -2px -2px 4px #FFFBF2',
    color: '#1A1714',
    textDecoration: 'none',
    fontSize: '15px',
    fontWeight: '500',
    cursor: 'pointer',
    transitionProperty: 'box-shadow, transform, background-color',
    transitionDuration: '120ms',
    ':hover': { backgroundColor: '#F8F3E9' },
    // Pressing pushes it into the sheet.
    ':active': {
      boxShadow: 'inset 2px 2px 4px rgba(90,74,52,0.30), inset -1px -1px 3px #FFFBF2',
      transform: 'translateY(1px)',
    },
    ':focus-visible': { outline: '2px solid #A8332A', outlineOffset: '3px' },
  },
  // The detected platform: same shape, brick face.
  buttonActive: {
    backgroundColor: '#A8332A',
    borderColor: '#8B2A22',
    color: '#F4EEE2',
    ':hover': { backgroundColor: '#B23A30' },
  },
  buttonDisabled: {
    opacity: '0.5',
    cursor: 'not-allowed',
    boxShadow: 'none',
    ':active': { boxShadow: 'none', transform: 'none' },
  },
  osLabel: { fontSize: '15px', fontWeight: '500' },
  sizeLabel: { fontSize: '12px', opacity: '0.75' },
  help: { fontSize: '13px', color: '#8C8474', margin: '0 0 6px 0' },
  link: {
    color: '#A8332A',
    textDecoration: 'none',
    borderBottom: '1px solid rgba(168,51,42,0.35)',
    ':hover': { borderBottomColor: '#A8332A' },
  },
  comingSoon: {
    display: 'inline-block',
    padding: '10px 14px',
    borderRadius: '8px',
    backgroundColor: '#EDE5D5',
    boxShadow: 'inset 1px 1px 3px rgba(90,74,52,0.22)',
  },
  comingSoonText: { fontSize: '13px', color: '#4A443C' },
});

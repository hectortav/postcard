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

const RELEASES_PAGE = 'https://github.com/index-zr0/postcard/releases';

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
    paddingLeft: '24px',
    paddingRight: '24px',
    paddingTop: '32px',
    paddingBottom: '32px',
    maxWidth: '960px',
    marginLeft: 'auto',
    marginRight: 'auto',
    boxSizing: 'border-box',
    '@media (max-width: 720px)': {
      paddingLeft: '20px',
      paddingRight: '20px',
      paddingTop: '24px',
      paddingBottom: '24px',
    },
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontWeight: '400',
    fontSize: '24px',
    color: '#1A1714',
    margin: '0 0 16px 0',
    letterSpacing: '-0.005em',
  },
  help: {
    fontSize: '14px',
    color: '#8C8474',
    margin: 0,
  },
  comingSoon: {
    backgroundColor: '#E9E1D0',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    padding: '20px',
  },
  comingSoonText: {
    fontSize: '15px',
    color: '#1A1714',
    margin: 0,
  },
  link: {
    color: '#A8332A',
    textDecoration: 'underline',
    textUnderlineOffset: '2px',
  },
  buttons: {
    display: 'grid',
    gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
    gap: '12px',
    '@media (max-width: 720px)': {
      gridTemplateColumns: '1fr',
    },
  },
  button: {
    display: 'flex',
    flexDirection: 'column',
    gap: '4px',
    backgroundColor: '#E9E1D0',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    padding: '16px',
    textDecoration: 'none',
    color: '#1A1714',
    transitionProperty: 'border-color, background-color, box-shadow',
    transitionDuration: '120ms',
    outline: 'none',
    ':focus-visible': {
      borderColor: '#A8332A',
      boxShadow: '0 0 0 2px #A8332A33',
    },
  },
  buttonActive: {
    borderColor: '#A8332A',
    backgroundColor: '#D8CDB7',
  },
  buttonDisabled: {
    opacity: 0.5,
    cursor: 'not-allowed',
  },
  osLabel: {
    fontSize: '15px',
    fontWeight: '600',
    color: '#1A1714',
  },
  sizeLabel: {
    fontSize: '12px',
    color: '#4A443C',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    wordBreak: 'break-all',
  },
});

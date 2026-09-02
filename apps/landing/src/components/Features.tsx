import stylex from '@stylexjs/stylex';

type Feature = {
  title: string;
  body: string;
};

const FEATURES: readonly Feature[] = [
  {
    title: 'Local-first',
    body: 'Your files never leave your wifi. The CLI runs the server in-process; the browser is the only client.',
  },
  {
    title: 'Encrypted',
    body: 'Optional AES-256-GCM at-rest, with the key carried in the URL hash so the server never sees it.',
  },
  {
    title: 'Cross-platform',
    body: 'macOS, Windows, Linux. One binary per platform, no Electron, no bundled browser.',
  },
];

export function Features() {
  return (
    <section className={stylex(styles.section)} aria-labelledby="features-heading">
      <h2 id="features-heading" className={stylex(styles.heading)}>What it does</h2>
      <ul className={stylex(styles.grid)}>
        {FEATURES.map((f) => (
          <li key={f.title} className={stylex(styles.card)}>
            <h3 className={stylex(styles.cardTitle)}>{f.title}</h3>
            <p className={stylex(styles.cardBody)}>{f.body}</p>
          </li>
        ))}
      </ul>
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
    margin: '0 0 24px 0',
  },
  grid: {
    listStyle: 'none',
    padding: 0,
    margin: 0,
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
    gap: '2px 32px',
    '@media (max-width: 640px)': { gridTemplateColumns: '1fr' },
  },
  // Flat on the sheet, separated by a rule. Depth is reserved for things you
  // can act on, so a read-only feature list stays printed.
  card: {
    paddingTop: '16px',
    paddingBottom: '16px',
    borderTop: '1px solid #E3D9C4',
  },
  cardTitle: {
    fontSize: '15px',
    fontWeight: '600',
    color: '#1A1714',
    margin: '0 0 5px 0',
  },
  cardBody: {
    fontSize: '14px',
    lineHeight: '1.55',
    color: '#4A443C',
    margin: 0,
  },
});

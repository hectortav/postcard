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
    margin: '0 0 20px 0',
    letterSpacing: '-0.005em',
  },
  grid: {
    listStyle: 'none',
    margin: 0,
    padding: 0,
    display: 'grid',
    gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
    gap: '20px',
    '@media (max-width: 720px)': {
      gridTemplateColumns: '1fr',
    },
  },
  card: {
    backgroundColor: '#E9E1D0',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    padding: '20px',
    boxSizing: 'border-box',
  },
  cardTitle: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    fontSize: '14px',
    fontWeight: '600',
    color: '#A8332A',
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    margin: '0 0 8px 0',
  },
  cardBody: {
    fontSize: '15px',
    lineHeight: '1.5',
    color: '#1A1714',
    margin: 0,
  },
});

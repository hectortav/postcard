import stylex from '@stylexjs/stylex';

const GITHUB_URL = 'https://github.com/hectortav/postcard';

export function Footer() {
  return (
    <footer className={stylex(styles.footer)}>
      <span className={stylex(styles.text)}>postcard, by air</span>
      <span className={stylex(styles.spacer)} />
      <a className={stylex(styles.link)} href={GITHUB_URL} target="_blank" rel="noreferrer">
        github
      </a>
    </footer>
  );
}

const styles = stylex.create({
  footer: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    width: '100%',
    maxWidth: '960px',
    marginLeft: 'auto',
    marginRight: 'auto',
    padding: '0 24px 40px',
    boxSizing: 'border-box',
    color: '#8C8474',
    fontSize: '13px',
    '@media (max-width: 640px)': { padding: '0 20px 32px' },
  },
  text: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
  },
  spacer: { flex: '1 1 auto' },
  dot: { display: 'none' },
  link: {
    color: '#A8332A',
    textDecoration: 'none',
    borderBottom: '1px solid rgba(168,51,42,0.35)',
    ':hover': { borderBottomColor: '#A8332A' },
    ':focus-visible': { outline: '2px solid #A8332A', outlineOffset: '3px' },
  },
});

import stylex from '@stylexjs/stylex';

const GITHUB_URL = 'https://github.com/index-zr0/postcard';

export function Footer() {
  return (
    <footer className={stylex(styles.footer)}>
      <span className={stylex(styles.text)}>postcard</span>
      <span className={stylex(styles.dot)} aria-hidden="true">·</span>
      <span className={stylex(styles.text)}>by air</span>
      <span className={stylex(styles.dot)} aria-hidden="true">·</span>
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
    gap: '8px',
    padding: '20px 24px',
    borderTop: '1px solid #D8CDB7',
    color: '#8C8474',
    fontSize: '12px',
    '@media (max-width: 720px)': {
      padding: '16px 20px',
      flexWrap: 'wrap',
    },
  },
  text: {
    color: '#8C8474',
  },
  dot: {
    color: '#D8CDB7',
  },
  link: {
    color: '#A8332A',
    textDecoration: 'underline',
    textUnderlineOffset: '2px',
    outline: 'none',
    ':focus-visible': {
      color: '#1A1714',
    },
  },
});

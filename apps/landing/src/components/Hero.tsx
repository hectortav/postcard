import stylex from '@stylexjs/stylex';

export function Hero() {
  return (
    <header className={stylex(styles.header)}>
      <div className={stylex(styles.stripe)} aria-hidden="true" />
      <h1 className={stylex(styles.title)}>send a file across the room&mdash;</h1>
      <p className={stylex(styles.subtitle)}>
        A local file-sharing CLI + web dashboard. Drop a file on one device,
        scan the QR on the other. No cloud, no signup.
      </p>
    </header>
  );
}

const styles = stylex.create({
  header: {
    paddingTop: '48px',
    paddingBottom: '32px',
    paddingLeft: '24px',
    paddingRight: '24px',
    textAlign: 'left',
    '@media (max-width: 720px)': {
      paddingTop: '32px',
      paddingBottom: '24px',
      paddingLeft: '20px',
      paddingRight: '20px',
    },
  },
  stripe: {
    height: '4px',
    marginLeft: '-24px',
    marginRight: '-24px',
    marginBottom: '32px',
    backgroundImage:
      'repeating-linear-gradient(90deg, #A8332A 0 12px, #1F3A5F 12px 24px, #A8332A 24px 36px)',
    '@media (max-width: 720px)': {
      marginLeft: '-20px',
      marginRight: '-20px',
    },
  },
  title: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontWeight: '400',
    fontSize: 'clamp(40px, 7vw, 64px)',
    lineHeight: '1.05',
    margin: '0 0 16px 0',
    color: '#1A1714',
    letterSpacing: '-0.01em',
    maxWidth: '16ch',
  },
  subtitle: {
    fontSize: '17px',
    lineHeight: '1.5',
    color: '#4A443C',
    margin: 0,
    maxWidth: '52ch',
  },
});

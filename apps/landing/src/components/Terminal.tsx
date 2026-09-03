import stylex from '@stylexjs/stylex';

/**
 * Static terminal-style code block. Communicates "this is a CLI" without
 * animation or a real shell. Renders a fake $ prompt, the command a
 * visitor would type, and a representative follow-up line that shows
 * the server binding a port + the bind URL the user would scan / open.
 */
export function Terminal() {
  return (
    <section className={stylex(styles.section)} aria-labelledby="terminal-heading">
      <h2 id="terminal-heading" className={stylex(styles.heading)}>Try it</h2>
      <pre className={stylex(styles.block)} aria-label="Terminal session">
        <code>
          <span className={stylex(styles.prompt)}>$</span>
          {' '}<span className={stylex(styles.cmd)}>npx -y sendme ~/Desktop/photo.jpg</span>
          {'\n'}
          <span className={stylex(styles.out)}>sendme 0.1.0 — listening on http://192.168.1.10:8080/</span>
          {'\n'}
          <span className={stylex(styles.out)}>scan the qr to open on another device ▣</span>
        </code>
      </pre>
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
  block: {
    backgroundColor: '#E9E1D0',
    color: '#1A1714',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    padding: '20px',
    margin: 0,
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '14px',
    lineHeight: '1.6',
    overflowX: 'auto',
    whiteSpace: 'pre',
  },
  prompt: {
    color: '#A8332A',
    fontWeight: '600',
  },
  cmd: {
    color: '#1A1714',
  },
  out: {
    color: '#4A443C',
  },
});

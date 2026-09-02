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
          {' '}<span className={stylex(styles.cmd)}>npx -y postcard ~/Desktop/photo.jpg</span>
          {'\n'}
          <span className={stylex(styles.out)}>postcard 0.1.0 — listening on http://192.168.1.10:8080/</span>
          {'\n'}
          <span className={stylex(styles.out)}>scan the qr to open on another device ▣</span>
        </code>
      </pre>
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
  block: {
    margin: 0,
    padding: '18px 20px',
    backgroundColor: '#EDE5D5',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
    boxShadow: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px #FFFBF2',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '13.5px',
    lineHeight: '1.7',
    overflowX: 'auto',
    color: '#1A1714',
  },
  prompt: { color: '#A8332A', userSelect: 'none' },
  cmd: { color: '#1A1714' },
  out: { color: '#8C8474' },
});

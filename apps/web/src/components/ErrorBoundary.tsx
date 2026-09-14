import { Component, type ComponentChildren } from 'preact';
import stylex from '@stylexjs/stylex';

type Props = { children: ComponentChildren };
type State = { error: Error | null };

/**
 * Last line of defence around the dashboard.
 *
 * Preact unmounts the whole tree when a render throws, so without this any single component
 * error left a blank off-white page with nothing on it and no hint that a server was still
 * running and reachable. The URL and QR code are the thing the user actually needs, so the
 * fallback keeps the address visible.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className={stylex(styles.wrap)} role="alert">
        <h1 className={stylex(styles.title)}>postcard hit a problem.</h1>
        <p className={stylex(styles.body)}>
          The dashboard could not draw itself, but the server is very likely still running.
          Reload the page, or open this address on another device:
        </p>
        <p className={stylex(styles.url)}>{typeof location === 'undefined' ? '' : location.host}</p>
        <p className={stylex(styles.detail)}>{error.message}</p>
      </div>
    );
  }
}

const styles = stylex.create({
  wrap: {
    maxWidth: '520px',
    margin: '0 auto',
    padding: '48px 20px',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    color: '#1A1714',
  },
  title: {
    margin: '0 0 12px 0',
    fontSize: '22px',
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
  },
  body: { margin: '0 0 12px 0', fontSize: '14px', lineHeight: 1.6, color: '#4A443C' },
  url: {
    margin: '0 0 16px 0',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '15px',
  },
  detail: { margin: 0, fontSize: '12px', color: '#8C8474' },
});

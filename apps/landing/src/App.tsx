import stylex from '@stylexjs/stylex';
import { Hero } from './components/Hero';
import { Features } from './components/Features';
import { ComparisonTable } from './components/ComparisonTable';
import { Terminal } from './components/Terminal';
import { Download } from './components/Download';
import { Footer } from './components/Footer';

export function App() {
  return (
    <div className={stylex(styles.shell)}>
      <Hero />
      <main className={stylex(styles.main)}>
        <Features />
        <ComparisonTable />
        <Terminal />
        <Download />
      </main>
      <Footer />
    </div>
  );
}

const styles = stylex.create({
  shell: {
    minHeight: '100vh',
    backgroundColor: '#E3D9C4',
    color: '#1A1714',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    display: 'flex',
    flexDirection: 'column',
    WebkitFontSmoothing: 'antialiased',
    textRendering: 'optimizeLegibility',
  },
  // Everything below the hero lives on one continuous sheet, so the page reads
  // as a card laid on the desk rather than a stack of floating panels.
  main: {
    flex: '1 0 auto',
    width: '100%',
    maxWidth: '960px',
    marginLeft: 'auto',
    marginRight: 'auto',
    marginBottom: '48px',
    boxSizing: 'border-box',
    backgroundColor: '#F4EEE2',
    border: '1px solid #D8CDB7',
    borderRadius: '14px',
    boxShadow: '0 1px 1px rgba(90,74,52,0.10), 0 10px 28px -12px rgba(90,74,52,0.38)',
    overflow: 'hidden',
    '@media (max-width: 1010px)': {
      marginLeft: '20px',
      marginRight: '20px',
      width: 'auto',
    },
  },
});

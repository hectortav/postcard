import stylex from '@stylexjs/stylex';
import { Hero } from './components/Hero';
import { Features } from './components/Features';
import { Terminal } from './components/Terminal';
import { Download } from './components/Download';
import { Footer } from './components/Footer';

export function App() {
  return (
    <div className={stylex(styles.shell)}>
      <Hero />
      <main className={stylex(styles.main)}>
        <Features />
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
    backgroundColor: '#F4EEE2',
    color: '#1A1714',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    display: 'flex',
    flexDirection: 'column',
    WebkitFontSmoothing: 'antialiased',
    textRendering: 'optimizeLegibility',
  },
  main: {
    flex: '1 0 auto',
  },
});

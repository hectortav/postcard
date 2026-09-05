import { render } from 'preact';
import { App } from './App';
import { tokens } from './tokens.stylex';

const root = document.getElementById('root');
if (!root) throw new Error('Missing #root element');

// Set a paper-coloured body background so the iOS home-indicator safe area
// doesn't show white below the shell on short pages, and so the body is the
// correct colour during the brief moment before Preact hydrates.
document.body.style.backgroundColor = tokens.colors.paper;
document.body.style.margin = '0';

// Stream dev-time errors, traces and console warnings to the Spotlight sidecar
// (see src/dev/spotlight.ts). Vite substitutes `false` for `import.meta.env.DEV`
// in a production build, so Rollup drops this branch and never emits the chunk
// — @sentry/browser stays out of the bundle that ships inside the JAR.
if (import.meta.env.DEV) {
  void import('./dev/spotlight').then(({ initSpotlight }) => initSpotlight());
}

render(<App />, root);

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

render(<App />, root);

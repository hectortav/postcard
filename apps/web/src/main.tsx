import { render } from 'preact';
import { App } from './App';

const root = document.getElementById('root');
if (!root) throw new Error('Missing #root element');
render(<App />, root);

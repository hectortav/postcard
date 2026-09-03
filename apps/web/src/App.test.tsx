import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/preact';
import { App } from './App';

describe('App', () => {
  it('renders the placeholder shell', () => {
    const { container } = render(<App />);
    expect(container.textContent).toBe('sendme loading…');
  });
});

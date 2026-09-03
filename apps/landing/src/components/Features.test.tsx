import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/preact';
import { Features } from './Features';

describe('Features', () => {
  it('renders all three feature titles', () => {
    render(<Features />);
    expect(screen.getByText('Local-first')).toBeTruthy();
    expect(screen.getByText('Encrypted')).toBeTruthy();
    expect(screen.getByText('Cross-platform')).toBeTruthy();
  });

  it('renders an h2 section heading', () => {
    const { container } = render(<Features />);
    const h2 = container.querySelector('h2');
    expect(h2?.textContent).toBe('What it does');
  });
});

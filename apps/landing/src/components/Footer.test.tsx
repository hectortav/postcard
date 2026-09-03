import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/preact';
import { Footer } from './Footer';

describe('Footer', () => {
  it('renders sendme, by air, and a github link', () => {
    render(<Footer />);
    const link = screen.getByRole('link', { name: /github/i });
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toBe('https://github.com/index-zr0/sendme');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noreferrer');
  });
});

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/preact';
import { Hero } from './Hero';

describe('Hero', () => {
  it('renders the italic-serif headline with the em-dash suffix', () => {
    render(<Hero />);
    const h1 = screen.getByRole('heading', { level: 1 });
    expect(h1.textContent).toMatch(/send a file across the room/);
    // The em-dash (—) ends the line, breaking the all-italic-period pattern.
    expect(h1.textContent).toMatch(/—$/);
  });

  it('renders the marketing sub-line', () => {
    const { container } = render(<Hero />);
    // Scope to the rendered <p> directly to avoid the <meta description> in <head>.
    const sub = container.querySelector('p');
    expect(sub?.textContent).toMatch(/no cloud, no signup/i);
  });
});

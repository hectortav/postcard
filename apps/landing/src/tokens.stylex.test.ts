import { describe, it, expect } from 'vitest';
import { tokens } from './tokens.stylex';

// Helper: parse "24px" -> 24, "1.5rem" -> 1.5 (number-only is fine for the
// values in this token set; we don't currently use rem/em).
function parsePx(value: string | number): number {
  if (typeof value === 'number') return value;
  const m = /^\s*(-?\d+(?:\.\d+)?)\s*(px|rem|em)?\s*$/.exec(value);
  if (!m) throw new Error(`Cannot parse length value: ${JSON.stringify(value)}`);
  return Number(m[1]);
}

describe('tokens', () => {
  it('exposes a paper background colour as a 6-digit hex color', () => {
    expect(tokens.colors).toBeDefined();
    expect(typeof tokens.colors.paper).toBe('string');
    expect(tokens.colors.paper).toMatch(/^#[0-9a-fA-F]{6}$/);
  });

  it('exposes a single airmail-brick accent in the red family', () => {
    expect(typeof tokens.colors.accent).toBe('string');
    expect(tokens.colors.accent).toMatch(/^#[0-9a-fA-F]{6}$/);
    // The accent should be reddish (R channel > G, B channels)
    const r = parseInt(tokens.colors.accent.slice(1, 3), 16);
    const g = parseInt(tokens.colors.accent.slice(3, 5), 16);
    const b = parseInt(tokens.colors.accent.slice(5, 7), 16);
    expect(r).toBeGreaterThan(g);
    expect(r).toBeGreaterThan(b);
  });

  it('exposes a space scale with monotonically increasing sizes (xs < sm < md < lg < xl < xxl)', () => {
    expect(tokens.space).toBeDefined();
    const sizes = {
      xs: parsePx(tokens.space.xs),
      sm: parsePx(tokens.space.sm),
      md: parsePx(tokens.space.md),
      lg: parsePx(tokens.space.lg),
      xl: parsePx(tokens.space.xl),
      xxl: parsePx(tokens.space.xxl),
    };
    expect(sizes.xs).toBeGreaterThan(0);
    expect(sizes.sm).toBeGreaterThan(sizes.xs);
    expect(sizes.md).toBeGreaterThan(sizes.sm);
    expect(sizes.lg).toBeGreaterThan(sizes.md);
    expect(sizes.xl).toBeGreaterThan(sizes.lg);
    expect(sizes.xxl).toBeGreaterThan(sizes.xl);
  });

  it('exposes a small radius scale suitable for flat-paper UI', () => {
    expect(tokens.radius).toBeDefined();
    const r = {
      sm: parsePx(tokens.radius.sm),
      md: parsePx(tokens.radius.md),
      lg: parsePx(tokens.radius.lg),
    };
    expect(r.sm).toBeGreaterThan(0);
    expect(r.md).toBeGreaterThanOrEqual(r.sm);
    expect(r.lg).toBeGreaterThanOrEqual(r.md);
    // Flat-paper aesthetic: no pill-sized radii.
    expect(r.lg).toBeLessThanOrEqual(16);
  });

  it('exposes three role-based font stacks: display, body, and mono', () => {
    expect(tokens.font).toBeDefined();
    expect(typeof tokens.font.display).toBe('string');
    expect(typeof tokens.font.body).toBe('string');
    expect(typeof tokens.font.mono).toBe('string');
    // The body stack should be sans-serif and use system-ui
    expect(tokens.font.body).toContain('system-ui');
    // The display stack should be serif (the signature element of the page)
    expect(tokens.font.display.toLowerCase()).toContain('serif');
  });
  // Relative luminance, sRGB coefficients. Used to assert surface ordering rather
  // than pinning hex values, so the palette can be retuned without breaking tests.
  const lum = (hex: string): number => {
    const r = parseInt(hex.slice(1, 3), 16) / 255;
    const g = parseInt(hex.slice(3, 5), 16) / 255;
    const b = parseInt(hex.slice(5, 7), 16) / 255;
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };

  it('orders its paper surfaces by height so a card can lift off the desk', () => {
    // The whole letterpress system depends on the ground being darker than the
    // card. If desk >= paper, nothing can visually sit on anything.
    expect(lum(tokens.colors.desk)).toBeLessThan(lum(tokens.colors.paper));
    // paperHi is the lit edge of an embossed element, so it must out-shine paper.
    expect(lum(tokens.colors.paperHi)).toBeGreaterThan(lum(tokens.colors.paper));
  });

  it('exposes letterpress depth shadows that encode direction', () => {
    // Debossed = pressed into the sheet (inputs). Embossed = raised out of it (actions).
    expect(tokens.shadow.deboss).toContain('inset');
    expect(tokens.shadow.emboss).not.toContain('inset');
    expect(tokens.shadow.lift).toBeDefined();
  });

  it('keeps a pressed state that is darker than the resting accent', () => {
    expect(lum(tokens.colors.accentDeep)).toBeLessThan(lum(tokens.colors.accent));
  });
});

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
  it('exposes a colors object whose bg is a 6-digit hex color', () => {
    expect(tokens.colors).toBeDefined();
    expect(typeof tokens.colors.bg).toBe('string');
    expect(tokens.colors.bg).toMatch(/^#[0-9a-fA-F]{6}$/);
  });

  it('exposes a space scale with monotonically increasing sizes (xs < sm < md < lg < xl)', () => {
    expect(tokens.space).toBeDefined();
    const sizes = {
      xs: parsePx(tokens.space.xs),
      sm: parsePx(tokens.space.sm),
      md: parsePx(tokens.space.md),
      lg: parsePx(tokens.space.lg),
      xl: parsePx(tokens.space.xl),
    };
    expect(sizes.xs).toBeGreaterThan(0);
    expect(sizes.sm).toBeGreaterThan(sizes.xs);
    expect(sizes.md).toBeGreaterThan(sizes.sm);
    expect(sizes.lg).toBeGreaterThan(sizes.md);
    expect(sizes.xl).toBeGreaterThan(sizes.lg);
  });

  it('exposes a positive radius scale', () => {
    expect(tokens.radius).toBeDefined();
    const r = {
      sm: parsePx(tokens.radius.sm),
      md: parsePx(tokens.radius.md),
      lg: parsePx(tokens.radius.lg),
    };
    expect(r.sm).toBeGreaterThan(0);
    expect(r.md).toBeGreaterThan(r.sm);
    expect(r.lg).toBeGreaterThan(r.md);
  });

  it('exposes a sans font stack that includes system-ui and a separate mono stack', () => {
    expect(tokens.font).toBeDefined();
    expect(typeof tokens.font.sans).toBe('string');
    expect(tokens.font.sans).toContain('system-ui');
    expect(typeof tokens.font.mono).toBe('string');
  });
});

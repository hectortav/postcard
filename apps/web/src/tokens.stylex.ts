/**
 * Design tokens for the sendme web UI.
 *
 * Note on StyleX 0.7.5: the API does not expose `stylex.defineConsts` (it
 * exists only in newer StyleX releases). We fall back to a plain TypeScript
 * const so values are still tree-shakeable and typed. These tokens are
 * intended to be used in regular TSX code (e.g. style attributes outside of
 * `stylex.create(...)` blocks, dynamic styles, and non-CSS consumers). Inside
 * `stylex.create(...)` blocks, the babel plugin requires literal values and
 * does not resolve variable references, so callers should inline literals
 * there or use `stylex.defineVars` (also exposed by StyleX 0.7.5) when
 * theming is needed.
 */

export const tokens = {
  colors: {
    bg: '#0b0d10',
    panel: '#161a20',
    text: '#e7ecf3',
    muted: '#8b95a5',
    accent: '#5aa9ff',
    danger: '#ff6b6b',
  },
  space: {
    xs: '4px',
    sm: '8px',
    md: '16px',
    lg: '24px',
    xl: '32px',
  },
  radius: {
    sm: '4px',
    md: '8px',
    lg: '16px',
  },
  font: {
    sans: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    mono: 'ui-monospace, "SF Mono", Menlo, monospace',
  },
} as const;

export type Tokens = typeof tokens;

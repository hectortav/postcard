/**
 * Design tokens for the sendme web UI — the "Civvies" direction.
 *
 * Subject: a one-shot way to move a file from one device to another on the
 * same WiFi. The page is a postcard, not a dashboard: paper, ink, a
 * single airmail-red accent. Distinct from the dark-mode-with-acid-blue
 * default that AI tooling tends to produce.
 *
 * Note on StyleX 0.7.5: the API does not expose `stylex.defineConsts`
 * (it exists only in newer StyleX releases). We fall back to a plain
 * TypeScript const so values are still tree-shakeable and typed. These
 * tokens are intended to be used in regular TSX code (style attributes
 * outside `stylex.create(...)` blocks, dynamic styles, and non-CSS
 * consumers). Inside `stylex.create(...)` blocks, the babel plugin
 * requires literal values and does not resolve variable references, so
 * callers should inline literals there or use `stylex.defineVars` (also
 * exposed by StyleX 0.7.5) when theming is needed.
 */

export const tokens = {
  colors: {
    paper: '#F4EEE2',
    paperDeep: '#E9E1D0',
    paperEdge: '#D8CDB7',
    ink: '#1A1714',
    inkSoft: '#4A443C',
    inkMute: '#8C8474',
    accent: '#A8332A',
    accentSoft: '#C45F4A',
    success: '#2D6A4F',
    danger: '#8B2A2A',
  },
  space: {
    xs: '4px',
    sm: '8px',
    md: '12px',
    lg: '20px',
    xl: '32px',
    xxl: '48px',
  },
  radius: {
    sm: '2px',
    md: '6px',
    lg: '12px',
  },
  font: {
    display: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    body: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    mono: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  },
  size: {
    maxWidth: '640px',
    qr: 'min(72vw, 280px)',
    rowMin: '56px',
    stripe: '6px',
  },
} as const;

export type Tokens = typeof tokens;

/**
 * Design tokens for postcard — the "Letterpress" direction.
 *
 * Subject: the marketing page for a one-shot way to move a file between two
 * devices on the same WiFi. The page is a postcard, not a landing page.
 *
 * The three requested treatments resolve into one material rather than three
 * stacked trends: paper under a press.
 *
 *   skeuomorphism → the materials themselves: paper stock, a perforated
 *                   stamp (the app icon), a card resting on a desk.
 *   neumorphism   → what paper does under a press. Inputs are DEBOSSED into
 *                   the sheet, actions are EMBOSSED out of it. Soft dual
 *                   shadows, but earned by the material instead of applied
 *                   to every element — and every surface keeps a real
 *                   hairline border, so an edge never depends on shadow
 *                   alone. That is the usual accessibility failure of
 *                   neumorphism and it is designed out here.
 *   glass         → exactly one layer, the PIN lock panel. One glass moment.
 *
 * Colours are taken from the app icon (icons/postcard.svg): paper #F4EEE2
 * and airmail brick #A8332A. `desk` is the load-bearing addition — the shell
 * used to be the same colour as a card, so nothing could lift off anything.
 *
 * Note on StyleX 0.7.5: the API does not expose `stylex.defineConsts`
 * (it exists only in newer StyleX releases). We fall back to a plain
 * TypeScript const so values are still tree-shakeable and typed. Inside
 * `stylex.create(...)` blocks the babel plugin requires literal values and
 * does not resolve variable references, so callers must inline literals
 * there and use these tokens for dynamic styles and non-CSS consumers.
 */

export const tokens = {
  colors: {
    // Surfaces, ordered by height: desk < paper < paperHi.
    desk: '#E3D9C4',
    paper: '#F4EEE2',
    paperHi: '#FFFBF2',
    paperDeep: '#E9E1D0',
    paperEdge: '#D8CDB7',
    ink: '#1A1714',
    inkSoft: '#4A443C',
    inkMute: '#8C8474',
    accent: '#A8332A',
    accentDeep: '#8B2A22',
    accentSoft: '#C45F4A',
    success: '#2D6A4F',
    danger: '#8B2A2A',
  },
  /**
   * Letterpress depth. Direction carries meaning, so these are not
   * interchangeable: `deboss` marks something you put things into, `emboss`
   * marks something you press, `lift` marks a sheet resting on the desk.
   */
  shadow: {
    deboss: 'inset 2px 2px 5px rgba(90,74,52,0.26), inset -2px -2px 4px #FFFBF2',
    debossDeep: 'inset 3px 3px 8px rgba(90,74,52,0.32), inset -2px -2px 5px #FFFBF2',
    emboss: '2px 2px 5px rgba(90,74,52,0.24), -2px -2px 4px #FFFBF2',
    embossPressed: 'inset 2px 2px 4px rgba(90,74,52,0.30), inset -1px -1px 3px #FFFBF2',
    lift: '0 1px 1px rgba(90,74,52,0.10), 0 10px 28px -10px rgba(90,74,52,0.40)',
    glass: '0 8px 32px -8px rgba(26,23,20,0.30)',
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
    maxWidth: '960px',
    card: '600px',
    stripe: '4px',
  },
} as const;

export type Tokens = typeof tokens;

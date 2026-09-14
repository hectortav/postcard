import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';

// Injects the StyleX-generated `stylex.css` into the built index.html.
// The StyleX unplugin writes the file at `assets/stylex.css` during
// build, but does NOT inject a corresponding <link> in production
// (`transformIndexHtml` is gated to `devMode === 'full'`). We do it
// here, with a path that respects Vite's `base` (so GitHub Pages
// hosting at `/postcard/` resolves the asset correctly).
/**
 * GitHub Pages serves this site under the repository name, so every asset URL carries that
 * prefix. Single source of truth: the Vite `base` and the injected stylesheet both read it.
 */
const BASE = '/postcard/';

const injectStylexCss = (): import('vite').Plugin => ({
  name: 'postcard:inject-stylex-css',
  apply: 'build',
  enforce: 'post',
  transformIndexHtml: {
    order: 'post',
    handler(html) {
      // Derived from BASE rather than written out a second time: these used to be two
      // separate literals that had to be kept in step by hand, and the comment saying so
      // named the wrong repository owner.
      const href = `${BASE}assets/stylex.css`;
      const link = `<link rel="stylesheet" href="${href}">`;
      if (html.includes(`href="${href}"`) || html.includes(`href="${BASE}stylex.css"`)) return html;
      return html.replace('</head>', `  ${link}\n  </head>`);
    },
  },
});

export default defineConfig({
  // The GitHub Pages URL prefix; see BASE.
  base: BASE,
  plugins: [
    preact(),
    stylexPlugin.vite({ unstable_moduleResolution: { type: 'commonJS', rootDir: import.meta.dirname } }),
    injectStylexCss(),
  ],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
  },
  preview: {
    port: 4173,
  },
});

import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Injects the StyleX-generated `stylex.css` into the built index.html.
// The StyleX unplugin writes the file at `assets/stylex.css` during
// build, but does NOT inject a corresponding <link> in production
// (`transformIndexHtml` is gated to `devMode === 'full'`). We do it
// here, with a path that respects Vite's `base` (so GitHub Pages
// hosting at `/sendme/` resolves the asset correctly).
const injectStylexCss = (): import('vite').Plugin => ({
  name: 'sendme:inject-stylex-css',
  apply: 'build',
  enforce: 'post',
  transformIndexHtml: {
    order: 'post',
    handler(html) {
      // We hardcode the prefix here because the `base` config is
      // `/sendme/` (see the top of this file); if the base ever
      // changes, update this string in lockstep.
      const link = '<link rel="stylesheet" href="/sendme/assets/stylex.css">';
      if (html.includes('href="/sendme/assets/stylex.css"') || html.includes('href="/sendme/stylex.css"')) return html;
      return html.replace('</head>', `  ${link}\n  </head>`);
    },
  },
});

export default defineConfig({
  // `base: "/sendme/"` is the GitHub Pages URL prefix. The repo name on
  // GitHub Pages is `sendme` (https://index-zr0.github.io/sendme/), so
  // every asset URL needs to be prefixed with `/sendme/` to resolve.
  // If the repo is ever renamed, this string must change in lockstep.
  base: '/sendme/',
  plugins: [
    preact(),
    stylexPlugin.vite({ unstable_moduleResolution: { type: 'commonJS', rootDir: __dirname } }),
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

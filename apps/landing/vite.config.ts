import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';

// Injects the StyleX-generated `stylex.css` into the built index.html.
// The StyleX unplugin writes the file at `assets/stylex.css` during
// build, but does NOT inject a corresponding <link> in production
// (`transformIndexHtml` is gated to `devMode === 'full'`). We do it
// here, with a path that respects Vite's `base` (so GitHub Pages
// hosting at `/postcard/` resolves the asset correctly).
const injectStylexCss = (): import('vite').Plugin => ({
  name: 'postcard:inject-stylex-css',
  apply: 'build',
  enforce: 'post',
  transformIndexHtml: {
    order: 'post',
    handler(html) {
      // We hardcode the prefix here because the `base` config is
      // `/postcard/` (see the top of this file); if the base ever
      // changes, update this string in lockstep.
      const link = '<link rel="stylesheet" href="/postcard/assets/stylex.css">';
      if (html.includes('href="/postcard/assets/stylex.css"') || html.includes('href="/postcard/stylex.css"')) return html;
      return html.replace('</head>', `  ${link}\n  </head>`);
    },
  },
});

export default defineConfig({
  // `base: "/postcard/"` is the GitHub Pages URL prefix. The repo name on
  // GitHub Pages is `postcard` (https://index-zr0.github.io/postcard/), so
  // every asset URL needs to be prefixed with `/postcard/` to resolve.
  // If the repo is ever renamed, this string must change in lockstep.
  base: '/postcard/',
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

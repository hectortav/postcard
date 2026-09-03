import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Injects the StyleX-generated `stylex.css` into the built index.html.
// The plugin writes the file at outDir/stylex.css (or assets/stylex.css
// depending on `emptyOutDir` + the plugin's output config). The `assets/`
// variant is what we see in this build; the `stylex.css` at root is the
// fallback. We link whichever is present.
const injectStylexCss = (): import('vite').Plugin => ({
  name: 'sendme:inject-stylex-css',
  apply: 'build',
  enforce: 'post',
  transformIndexHtml: {
    order: 'post',
    handler(html) {
      const link = '<link rel="stylesheet" href="/assets/stylex.css">';
      if (html.includes('href="/assets/stylex.css"') || html.includes('href="/stylex.css"')) return html;
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

import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Injects the StyleX-generated `stylex.css` into the built index.html.
// The plugin writes the file at outDir/stylex.css (or assets/stylex.css
// depending on `emptyOutDir` + the plugin's output config). The `assets/`
// variant is what we see in this build; the `stylex.css` at root is the
// fallback. We link whichever is present.
const injectStylexCss = (): import('vite').Plugin => ({
  name: 'postcard:inject-stylex-css',
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
  plugins: [
    preact(),
    stylexPlugin.vite({ unstable_moduleResolution: { type: 'commonJS', rootDir: __dirname } }),
    injectStylexCss(),
  ],
  build: {
    outDir: resolve(__dirname, '../cli-server/src/main/resources/public'),
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});

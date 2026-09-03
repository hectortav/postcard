import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [preact(), stylexPlugin.vite({ unstable_moduleResolution: { type: 'commonJS', rootDir: __dirname } })],
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

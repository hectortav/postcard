import { defineConfig } from 'vitest/config';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [preact(), stylexPlugin.vite({ unstable_moduleResolution: { type: 'commonJS', rootDir: __dirname } })],
  test: {
    environment: 'happy-dom',
    globals: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});

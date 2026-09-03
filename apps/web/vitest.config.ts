import { defineConfig } from 'vitest/config';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';

export default defineConfig({
  plugins: [preact(), stylexPlugin.vite({ unstable_moduleResolution: { type: 'commonJS', rootDir: __dirname } })],
  test: {
    environment: 'happy-dom',
    globals: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});

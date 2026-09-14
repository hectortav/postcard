import { defineConfig } from 'vitest/config';
import preact from '@preact/preset-vite';
import stylexPlugin from '@stylexjs/unplugin';

export default defineConfig({
  plugins: [
    preact(),
    stylexPlugin.vite({
      unstable_moduleResolution: { type: 'commonJS', rootDir: import.meta.dirname },
    }),
  ],
  test: {
    environment: 'happy-dom',
    globals: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // Coverage gates.
    //
    // Aggregate rather than per-file: App.tsx is the shell that wires everything together and
    // is exercised mostly through the components it renders, so a per-file floor would gate
    // hardest on the file where a unit test says least.
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/**/*.spec.{ts,tsx}', 'src/main.tsx', 'src/types.ts'],
      thresholds: {
        // Measured 87.7 statements / 77.6 branches / 87.1 functions / 91.1 lines. The floors
        // sit just under that so they ratchet. They were 70 across the board while the README
        // advertised 90, which was true of neither.
        lines: 88,
        branches: 75,
        functions: 85,
        statements: 85,
      },
    },
  },
});

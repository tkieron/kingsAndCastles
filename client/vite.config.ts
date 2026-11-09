import { defineConfig } from 'vite';

// GitHub Pages serves the game from /<repo>/, so assets use relative paths.
export default defineConfig({
  base: './',
  build: { outDir: 'dist', assetsInlineLimit: 0 },
  test: { environment: 'jsdom' },
} as Parameters<typeof defineConfig>[0]);

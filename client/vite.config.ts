import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

// The Java engine compiled to JavaScript by TeaVM (mvn -pl browser -am package), for the offline version.
// When it was not built, a stand-in keeps the online client building and testing without Java.
const engine = fileURLToPath(new URL('../browser/target/js/kc-engine.js', import.meta.url));
const engineMissing = fileURLToPath(new URL('./src/local/engine-missing.js', import.meta.url));

// `vite build --mode offline` makes the version with no server: one HTML file with everything inside.
export default defineConfig(({ mode }) => {
  const offline = mode === 'offline';
  return {
    base: './',
    resolve: { alias: { 'kc-engine': existsSync(engine) ? engine : engineMissing } },
    build: offline
      ? {
        outDir: 'dist-offline',
        copyPublicDir: false,
        assetsInlineLimit: Number.MAX_SAFE_INTEGER,
        cssCodeSplit: false,
        modulePreload: false,
        rollupOptions: { output: { inlineDynamicImports: true } },
      }
      : { outDir: 'dist', assetsInlineLimit: 0 },
    test: { environment: 'jsdom' },
  };
}) as unknown as ReturnType<typeof defineConfig>;

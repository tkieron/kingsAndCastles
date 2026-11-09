// Puts the offline build (dist-offline/) into a single HTML file that opens straight from the disk,
// with no server and no internet (except the web fonts, which fall back to the system font).
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const dir = new URL('../dist-offline/', import.meta.url).pathname;
let html = readFileSync(join(dir, 'index.html'), 'utf8');
const assets = join(dir, 'assets');
const files = readdirSync(assets);

// drop the app-install bits: an HTML file on the disk cannot be installed or use a service worker
html = html.replace(/\s*<link rel="manifest"[^>]*>/, '');
const icon = readFileSync(new URL('../public/icon-192.png', import.meta.url)).toString('base64');
html = html.replaceAll('./icon-192.png', `data:image/png;base64,${icon}`);

for (const f of files.filter((f) => f.endsWith('.css'))) {
  const css = readFileSync(join(assets, f), 'utf8');
  html = html.replace(new RegExp(`<link rel="stylesheet"[^>]*${f}">`), () => `<style>${css}</style>`);
}
for (const f of files.filter((f) => f.endsWith('.js'))) {
  const js = readFileSync(join(assets, f), 'utf8').replaceAll('</script', '<\\/script');
  // a classic inline module at the end of <body>, so the page is ready when it runs
  html = html.replace(new RegExp(`\\s*<script type="module" crossorigin src="[^"]*${f}"></script>`), '');
  html = html.replace('</body>', () => `<script type="module">${js}</script>\n</body>`);
}
if (/src="\.\/assets|href="\.\/assets/.test(html)) throw new Error('Some asset was not put inside the page');
writeFileSync(join(dir, 'kings-and-castles-offline.html'), html);
console.log(`dist-offline/kings-and-castles-offline.html (${Math.round(html.length / 1024)} KB)`);

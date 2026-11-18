// End-to-end check of the server version: the Java server serves the game page, a host opens a room,
// two children join from their own browsers, everyone plays turn 1, and the game moves on to turn 2.
//
//   KC_URL=http://localhost:8080 node e2e/server-game.mjs
//
// Screenshots land in e2e/out/. PW_CHROMIUM can point at an installed Chromium instead of Playwright's own.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const url = (process.env.KC_URL ?? 'http://localhost:8080').replace(/\/$/, '') + '/';
const out = new URL('./out/', import.meta.url).pathname;
mkdirSync(out, { recursive: true });

const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
const problems = [];
const shot = (page, name) => page.screenshot({ path: out + name + '.png' });

async function open(who) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'pl-PL' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => problems.push(`${who}: ${e.message}`));
  page.on('console', (m) => m.type() === 'error' && problems.push(`${who} console: ${m.text()}`));
  await page.goto(url);
  return page;
}

function check(ok, what) {
  if (!ok) throw new Error(what);
  console.log('ok:', what);
}

try {
  const host = await open('host');
  await host.getByRole('button', { name: 'Załóż pokój' }).click();
  await host.getByText('Podaj graczom ten kod').waitFor();
  const code = ((await host.textContent('body')).match(/room=(\d{6})/) ?? [])[1];
  check(code, `the host gets a room code (${code})`);

  const kids = [];
  for (const name of ['Ania', 'Bartek']) {
    const page = await open(name);
    const form = page.locator('form').first();
    await form.locator('input').nth(0).fill(code);
    await form.locator('input').nth(1).fill(name);
    await form.locator('.who-pick').first().click();
    await form.locator('.avatar-pick').first().click();
    await form.getByRole('button', { name: 'Dołącz' }).click();
    kids.push(page);
  }
  await host.getByText('Bartek').first().waitFor();
  check(true, 'both children sit in the lobby');
  await shot(host, '1-lobby');

  await host.getByRole('button', { name: 'Zaczynamy!' }).click();
  for (const page of kids) {
    await page.getByText('Wiosna').first().waitFor();
    await page.getByText(/1\/20/).first().waitFor();
  }
  check(true, 'the game starts on every screen');
  await shot(kids[0], '2-turn-1');

  for (const page of kids) {
    // Grosik's welcome tour opens a moment after the game screen; skip it once it is there
    const tour = page.getByRole('dialog', { name: 'Jak grać' });
    if (await tour.waitFor({ timeout: 8000 }).then(() => true, () => false)) {
      await tour.getByRole('button', { name: 'Pomiń' }).click();
      await tour.waitFor({ state: 'hidden' });
    }
    await page.getByRole('button', { name: /Gotowe/ }).first().click();
  }
  for (const page of kids) {
    await page.getByText('Koniec tury 1!').waitFor({ timeout: 15000 });
    await page.getByText(/2\/20/).first().waitFor();
  }
  check(true, 'the turn ends when both children are ready, and turn 2 begins');
  await shot(kids[0], '3-turn-2');

  check(problems.length === 0, `no errors in the browsers${problems.length ? ': ' + problems.join('; ') : ''}`);
} finally {
  await browser.close();
}

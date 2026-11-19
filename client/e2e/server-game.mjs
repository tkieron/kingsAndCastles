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

// PW_HOST_RULES (e.g. "MAP kc.pointsapp.pl 127.0.0.1") lets a test copy of the server stand in for the real address
const hostRules = process.env.PW_HOST_RULES;
const browser = await chromium.launch({
  ...(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {}),
  ...(hostRules ? { args: [`--host-resolver-rules=${hostRules}`, "--no-proxy-server"] } : {}),
});
const problems = [];
const shot = (page, name) => page.screenshot({ path: out + name + '.png' });

async function open(who) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'pl-PL', ignoreHTTPSErrors: !!hostRules });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => problems.push(`${who}: ${e.message}`));
  page.on('console', (m) => {
    // with a test certificate the service worker cannot be fetched; that is the test setup, not the game
    if (m.type() !== 'error' || (hostRules && /SSL certificate error/.test(m.text()))) return;
    // the wrong return code typed on purpose comes back as 400; the browser logs every 400
    if (who === 'typed' && /status of 400/.test(m.text())) return;
    problems.push(`${who} console: ${m.text()}`);
  });
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

  await kids[0].getByRole('button', { name: /Dalej: tura 2/ }).click();
  // Ania takes her game to a phone: the 📱 card shows her return code and a QR code with the link
  await kids[0].getByRole('button', { name: 'Graj na innym urządzeniu' }).first().click();
  const card = kids[0].getByRole('dialog', { name: /Graj na innym urządzeniu/ });
  await card.waitFor();
  const resumeCode = await card.locator('[data-code]').getAttribute('data-code');
  check(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(resumeCode ?? ''), `Ania sees her return code (${resumeCode}) and the QR code`);
  await shot(kids[0], '4-another-device');
  await card.getByRole('button', { name: 'Zamknij' }).click();

  // the QR code opens this link on the phone: straight back into Ania's kingdom, and the code leaves the address
  const phoneCtx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'pl-PL', ignoreHTTPSErrors: !!hostRules });
  const phone = await phoneCtx.newPage();
  phone.on('pageerror', (e) => problems.push(`phone: ${e.message}`));
  await phone.goto(`${url}?server=${encodeURIComponent(url.replace(/\/$/, ""))}#room=${code}&resume=${resumeCode}`);
  await phone.getByText(/2\/20/).first().waitFor();
  check(!phone.url().includes('resume='), 'the phone opens Ania\'s game from the link, and the code leaves the address');
  await shot(phone, '5-phone-back-in-game');

  // or typed by hand on another computer, small letters and all
  const typed = await open('typed');
  const back = typed.locator('form.resume-card');
  await back.locator('input').nth(0).fill(code);
  await back.locator('input').nth(1).fill('zzzz-zzzz');
  await back.getByRole('button', { name: 'Wracam' }).click();
  await typed.getByText('Ten kod powrotu nie pasuje').waitFor();
  check(true, 'a wrong code is refused with a clear message');
  await back.locator('input').nth(1).fill(resumeCode.toLowerCase());
  await back.getByRole('button', { name: 'Wracam' }).click();
  await typed.getByText(/2\/20/).first().waitFor();
  check(true, 'the typed return code brings Ania back too');

  check(problems.length === 0, `no errors in the browsers${problems.length ? ': ' + problems.join('; ') : ''}`);
} finally {
  await browser.close();
}

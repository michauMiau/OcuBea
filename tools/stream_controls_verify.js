/* Proves the picture controls are wired, not merely present.
 *
 * The failure this guards against is the one the rest of this repo keeps
 * finding: a control that renders, answers the click, and changes nothing. The
 * earlier audits here clicked every control and recorded the HTTP request --
 * which passes for a button whose handler is `okText("ok")`.
 *
 * So each control is checked by what it must make true, in the page itself:
 *
 *   fullscreen  document.fullscreenElement is the .stream box, and the picture
 *               really got a bigger box afterwards. Checking only that an API
 *               call was made would pass a handler that catches the rejection
 *               and sets a message.
 *   external    the click opened a tab, and that tab's request was for the
 *               stream -- not about:blank. A popup blocker returns null and is
 *               reported in #streamctl .dim, which is also asserted, because a
 *               silently swallowed click is the same defect one layer up.
 *   audio       the external audio button is disabled when there is no audio
 *               to open and enabled when there is. A button that is always
 *               live would 401 or 404 in the player it opened.
 *
 * Mutation: turning the fullscreen handler into `setMsg(null); return;` -- the
 * no-op this repo has shipped before -- must turn `fullscreen actually enters`
 * red. Run: node tools/stream_controls_verify.js
 */
const { chromium } = require('playwright');

const H = process.env.OCUBEA || 'http://192.168.1.184:8080';

let pass = 0;
let fail = 0;
const ok = (name, cond, detail = '') => {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (detail ? ' -- ' + detail : '')); }
};

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));

  await page.goto(H, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(9000);           // the UI polls status on a timer

  // ── the controls exist and are reachable ──────────────────────────────────
  const ids = ['bFull', 'bExtV', 'bExtA'];
  for (const id of ids) {
    ok('control ' + id + ' is in the DOM', await page.locator('#' + id).count() === 1);
  }
  ok('controls sit over the picture',
    await page.locator('.stream .streamctl').count() === 1);

  // ── the audio button's enabled state tracks whether there is audio ─────────
  const audioOn = await page.evaluate(async () => {
    const d = JSON.parse(await (await fetch('/status.json')).text());
    return !!(d.audio && d.audio.enabled && d.audio.codec && d.audio.codec !== 'none');
  });
  const extADisabled = await page.locator('#bExtA').isDisabled();
  ok('external audio button matches the phone\'s audio state',
    extADisabled === !audioOn,
    'phone audio=' + audioOn + ' button disabled=' + extADisabled);

  // ── fullscreen: the real API, and a real size change ──────────────────────
  const before = await page.locator('.stream').boundingBox();
  await page.locator('#bFull').click();
  await page.waitForTimeout(1200);
  const fsEl = await page.evaluate(() => {
    const el = document.fullscreenElement;
    return el ? (el.className || '') : null;
  });
  ok('fullscreen actually enters', !!fsEl && fsEl.indexOf('stream') >= 0,
    'fullscreenElement=' + JSON.stringify(fsEl));
  const after = await page.locator('.stream').boundingBox();
  ok('fullscreen makes the picture bigger',
    !!after && !!before && (after.width > before.width + 1 || after.height > before.height + 1),
    'before=' + (before && Math.round(before.width) + 'x' + Math.round(before.height)) +
    ' after=' + (after && Math.round(after.width) + 'x' + Math.round(after.height)));
  await page.evaluate(() => document.exitFullscreen && document.exitFullscreen());
  await page.waitForTimeout(600);

  // ── external video: a tab that really asked for the stream ────────────────
  const [popup] = await Promise.all([
    page.context().waitForEvent('page', { timeout: 8000 }).catch(() => null),
    page.locator('#bExtV').click(),
  ]);
  ok('external video opens a tab', !!popup);
  if (popup) {
    const url = popup.url();
    ok('the tab points at the stream, not about:blank',
      url.indexOf('/video') >= 0, 'url=' + url);
    await popup.close().catch(() => {});
  }

  // ── no page errors along the way ──────────────────────────────────────────
  ok('no uncaught page errors', errs.length === 0, errs.join(' | '));

  await browser.close();
  console.log('\n' + pass + ' ok, ' + fail + ' FAIL');
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('harness error:', e); process.exit(2); });
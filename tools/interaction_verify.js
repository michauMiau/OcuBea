/* Clicks every toggle in the WebUI and checks the label stays in the chosen
 * language afterwards.
 *
 * This is the check that catches the bug the static render misses: several
 * buttons rebuild their own label in JavaScript when clicked, which bypasses
 * translatePage() entirely, so the label snaps back to English only after a
 * user touches it.
 *
 *   node tools/interaction_verify.js
 *
 * Exits non-zero if any label changes language, or if a click throws.
 * Requires: npm install --no-save playwright
 */
const path = require('path');
const { chromium } = require(path.join(__dirname, '..', 'node_modules', 'playwright'));

const PAGE = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'index.html');

/* Buttons that only relabel themselves. Clicking the stream or snapshot
 * buttons would start a camera that is not there under file://, so they are
 * left out — the point here is the label, not the device. */
const TOGGLES = ['bMode', 'bLL', 'bNight', 'bMotion', 'bRec'];

/* bNight, bMotion and bRec toggle a class rather than their own text — the
 * state shows on the Status card instead. Clicking one and seeing the same
 * label is correct for them, so it is not reported. */
const RELABELS = new Set(['bMode', 'bLL']);

/* A label is in the wrong language when it still contains English words. The
 * Polish translations all contain a diacritic or a word that is not English,
 * so the words to look for are the ones only the English version has. */
const ENGLISH_ONLY = [
  'stream', 'latency', 'mode', 'night', 'motion', 'record', 'torch',
  'stop', 'start', 'snapshot', 'live', 'offline', 'off', 'on'
];

(async () => {
  const browser = await chromium.launch();
  let problems = 0;

  for (const lang of ['en', 'pl']) {
    const page = await browser.newPage({ viewport: { width: 1000, height: 1200 } });
    const errors = [];
    page.on('pageerror', e => errors.push(String(e)));
    await page.goto('file://' + PAGE + '?lang=' + lang, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(400);

    for (const id of TOGGLES) {
      const before = await page.$eval('#' + id, e => e.textContent.trim()).catch(() => null);
      if (before === null) { console.log(lang + ' #" + id + ": brak przycisku'); continue; }
      await page.click('#' + id);
      await page.waitForTimeout(150);
      const after = await page.$eval('#' + id, e => e.textContent.trim());
      if (before === after && RELABELS.has(id)) {
        console.log(lang + ' #' + id + ': etykieta nie zmienila sie? "' + after + '"');
        problems++;
      } else if (before === after) {
        console.log(lang + ' #' + id + ': bez zmiany tekstu (przelacznik klasy) — "' + after + '"');
      }
      /* Only the Polish page is checked for English leftovers: the English
       * page is supposed to read that way. */
      if (lang === 'pl') {
        const low = after.toLowerCase();
        const hit = ENGLISH_ONLY.find(w => new RegExp('\\b' + w + '\\b').test(low));
        if (hit) {
          console.log('   ! #' + id + ' po kliknieciu: "' + after + '" (angielskie "' + hit + '")');
          problems++;
        } else {
          console.log(lang + ' #' + id + ': "' + after + '"');
        }
      } else {
        console.log(lang + ' #' + id + ': "' + after + '"');
      }
    }

    /* The status pill and the motion line are rebuilt by refresh(), which is
     * where a label most often reverts. */
    const dyn = await page.evaluate(() => {
      const pick = id => { const e = document.getElementById(id); return e ? e.textContent.trim() : null; };
      return [pick('statusPill'), pick('motionState')];
    });
    if (lang === 'pl') {
      dyn.forEach(s => {
        if (!s) return;
        const hit = ENGLISH_ONLY.find(w => new RegExp('\\b' + w + '\\b').test(s.toLowerCase()));
        if (hit) { console.log('   ! dynamiczny napis: "' + s + '" (angielskie "' + hit + '")'); problems++; }
      });
    }
    /* The three toggles that PUT to an endpoint fire a fetch, and there is no
     * server behind file://. That failure is expected here and says nothing
     * about labels, so only errors that are not a failed fetch are counted. */
    const real = errors.filter(e => !/Failed to fetch|NetworkError/i.test(e));
    if (real.length) { console.log('   ! bledy JS: ' + real.join(' | ')); problems += real.length; }
    await page.close();
  }

  await browser.close();
  console.log(problems ? '\n' + problems + ' problemow.' : '\nEtykiety trzymaja jezyk.');
  process.exit(problems ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });

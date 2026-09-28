/* Checks that index.html has a Polish translation for everything it renders.
 *
 * Renders the real page in Chromium, once per language, and compares the text
 * that comes out. Comparing is the only check that cannot fool itself: a
 * heuristic like "does this look English?" flags Polish words that happen to
 * have no diacritics — Klatki, Plik, Obraz — as untranslated, and the tempting
 * fix is then to weaken the detector instead of fixing the translation. Here a
 * line counts as untranslated exactly when both renders produce the same text.
 *
 * Playwright rather than jsdom because it is what actually runs the page, so a
 * broken script shows up as a page error instead of silently passing.
 *
 *   node tools/i18n_verify.js
 *
 * Exits non-zero if anything is still English under lang=pl, so it can gate a
 * build. Requires: npm install --no-save playwright
 */
const path = require('path');
const { chromium } = require(path.join(__dirname, '..', 'node_modules', 'playwright'));

const PAGE = path.join(__dirname, '..', 'app/src/main/assets/index.html');

/* Strings that stay English on purpose: product name, codec and unit names,
 * API paths, section headings that read as labels. These are compared too, so
 * they have to be listed — otherwise an untranslated page and a correctly
 * translated one are indistinguishable for exactly the words you care about.
 * The numeric ones are found by shape, not enumerated, because they change with
 * whatever the camera is currently doing. */
const STAYS_ENGLISH = new Set([
  'Ocu', 'Bea', 'MJPEG', 'FPS', 'RAM', 'Android', 'API', 'ONVIF SOAP',
  'Sepia', 'Status', 'Model', 'Web UI', 'ONVIF', 'QVGA', 'VGA', 'HD',
  'FullHD', 'Webcam', 'HLS', 'HTTP', 'GET', 'POST', 'SOAP'
]);

/* A resolution, a count, a duration, a zoom value, an en dash placeholder.
 * None of these are words, so there is nothing to translate — a rule about
 * shape beats a list that goes stale the moment a value changes. The
 * parenthesised acronym in a resolution ("1280×720 (HD)") is part of the same
 * shape, and "×" is a multiplication sign rather than the letter x. */
function isNumberLike(s) {
  const t = s.trim();
  if (/^[\d.,×:–—-]+([a-zA-Z×%]+)?$/.test(t)) return true;
  return /^\d+×\d+\s*\([A-Za-z0-9]+\)$/.test(t);
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1000, height: 1200 } });
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));

  /* lang goes in the URL because that is how the runtime picks a language and
   * it works from file:// with no server behind it. The walk runs inside the
   * page because the window handle does not survive a navigation. */
  const render = async lang => {
    await page.goto('file://' + PAGE + '?lang=' + lang, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(500);
    return page.evaluate(() => {
      const skip = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, CODE: 1, PRE: 1 };
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
      const out = [];
      let n;
      while ((n = walker.nextNode())) {
        const p = n.parentNode;
        if (!p || skip[p.nodeName]) continue;
        const s = n.nodeValue.replace(/\s+/g, ' ').trim();
        if (s) out.push(s);
      }
      return out;
    });
  };

  const en = await render('en');
  const pl = await render('pl');
  await browser.close();

  if (pageErrors.length) {
    console.log('--- bledy JS: ' + pageErrors.length + ' ---');
    pageErrors.forEach(e => console.log('  ! ' + e));
  }

  const countMismatch = en.length !== pl.length;
  if (countMismatch) {
    console.log('Strona ma różną liczbę napisów w dwóch językach: ' +
      en.length + ' vs ' + pl.length + ' — tłumaczenie zmienia strukturę DOM?');
  }
  console.log('=== EN: ' + en.length + ' widocznych napisow, PL: ' + pl.length + ' ===');

  const untranslated = [];
  const seen = new Set();
  /* Compare by position, and stop at the shorter list: a length mismatch
   * already failed above, and reading past it would report noise. */
  const n = Math.min(en.length, pl.length);
  for (let i = 0; i < n; i++) {
    if (en[i] === pl[i] && !STAYS_ENGLISH.has(en[i]) && !isNumberLike(en[i]) && !seen.has(en[i])) {
      seen.add(en[i]);
      untranslated.push(en[i]);
    }
  }

  console.log('--- bez tlumaczenia: ' + untranslated.length + ' ---');
  untranslated.forEach(t => console.log('  ! ' + t));
  const bad = untranslated.length || countMismatch || pageErrors.length;
  if (bad) {
    console.log('Dodaj te napisy do I18N.pl w index.html.');
    process.exit(1);
  }
  console.log('Wszystko przetlumaczone.');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });

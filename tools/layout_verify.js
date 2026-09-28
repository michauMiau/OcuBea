/* Renders index.html in Chromium at several widths and both languages, and
 * reports elements whose content does not fit.
 *
 * A translated label is usually longer than the English one, so a page that
 * passed a layout check in English can still clip in Polish. Checking only one
 * width hides that: the failure shows up at exactly the width where the longer
 * word crosses the button edge.
 *
 *   node tools/layout_verify.js
 *
 * Exits non-zero if anything overflows, so it can gate a build.
 * Requires: npm install --no-save playwright
 */
const path = require('path');
const { chromium } = require(path.join(__dirname, '..', 'node_modules', 'playwright'));

const PAGE = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'index.html');

/* 380 px is a phone held in portrait, 1000 px a desktop window. */
const WIDTHS = [360, 380, 414, 768, 1000];
const LANGS = ['en', 'pl'];

(async () => {
  const browser = await chromium.launch();
  let problems = 0;

  for (const lang of LANGS) {
    for (const width of WIDTHS) {
      const page = await browser.newPage({ viewport: { width, height: 1200 } });
      const errors = [];
      page.on('pageerror', e => errors.push(String(e)));
      await page.goto('file://' + PAGE + '?lang=' + lang, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(400);

      const bad = await page.evaluate(vw => {
        const out = [];
        document.querySelectorAll('button,label,dt,dd,h1,h2,p,span,a,div').forEach(e => {
          const r = e.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) return;
          // scrollWidth > clientWidth means the text does not fit its own box.
          if (e.scrollWidth > e.clientWidth + 2) {
            out.push('overflow  ' + e.tagName + ' "' + e.textContent.trim().slice(0, 30) +
                     '" (' + e.scrollWidth + '>' + e.clientWidth + ')');
          }
          // Past the right edge of the viewport.
          if (r.right > vw + 1) {
            out.push('poza ekranem  ' + e.tagName + ' "' + e.textContent.trim().slice(0, 30) +
                     '" right=' + Math.round(r.right));
          }
        });
        return out;
      }, width);

      const issues = bad.concat(errors.map(e => 'bled JS: ' + e));
      problems += issues.length;
      console.log(lang + ' @' + width + 'px: ' + (issues.length ? issues.length + ' problemy' : 'ok'));
      issues.slice(0, 5).forEach(x => console.log('   ! ' + x));
      await page.close();
    }
  }
  await browser.close();

  console.log(problems ? '\n' + problems + ' problemow do naprawy.' : '\nLayout czysty.');
  process.exit(problems ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });

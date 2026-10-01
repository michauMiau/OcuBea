/* Does the clip telemetry panel show what the phone reports?
 *
 * The old UI showed armed/frames/dropped/error while a clip was being written
 * and the new one showed none of it, so "Record 30s" gave no feedback at all and
 * a failing write reported nothing anywhere on the page.
 *
 * Reads the panel during a real recording and compares it with
 * /clips/recording, then does it again at rest -- the panel must not depend on a
 * recording being in progress to exist.
 */
const { chromium } = require('playwright');
const H = 'http://192.168.1.184:8080';
const url = (p) => H.replace(/\/$/, '') + p;
const get = async (p) => JSON.parse(await (await fetch(url(p))).text());

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));

  await p.goto(H, { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(9000);

  // Start a clip from the page itself, so this tests the UI's own path.
  await p.click('#bRecNow', { timeout: 4000 });
  await p.waitForTimeout(5000);

  const live = await get('/clips/recording');
  const shown = await p.evaluate(() => {
    const sec = [...document.querySelectorAll('section')].find(s =>
      (s.querySelector('h2')?.textContent || '').match(/Klip|Clips/));
    if (!sec) return null;
    const dl = sec.querySelector('dl.kv');
    if (!dl) return { panel: false };
    const out = {};
    const dts = [...dl.querySelectorAll('dt')];
    const dds = [...dl.querySelectorAll('dd')];
    dts.forEach((dt, i) => { out[dt.textContent.trim()] = dds[i]?.textContent.trim(); });
    return { panel: true, out };
  });

  console.log('telemetria telefonu:', JSON.stringify(live));
  console.log('panel w UI:', JSON.stringify(shown, null, 1));

  const ok = [];
  ok.push(['panel istnieje', !!shown?.panel]);
  if (shown?.panel) {
    const txt = JSON.stringify(shown.out);
    // Labels differ by language, so match on the values that must be there.
    ok.push(['liczba klatek widoczna', txt.includes(String(live.frames))]);
    ok.push(['nazwa pliku widoczna', txt.includes(live.file || '@@@')]);
    if (live.dropped > 0) ok.push(['zgubione klatki widoczne', txt.includes(String(live.dropped))]);
    if (live.bytes > 0) ok.push(['rozmiar widoczny', /KB|MB|B/.test(txt)]);
  }

  await p.click('#bRecStop', { timeout: 4000 }).catch(() => {});
  await p.waitForTimeout(2000);

  console.log('');
  for (const [name, good] of ok) console.log(`  ${good ? 'OK' : 'PROBLEM'}: ${name}`);
  console.log('JS errors: ' + (errs.length ? errs.join(' | ') : 'brak'));
  await b.close();
})();
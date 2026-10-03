/* Feature-parity audit: what the old vanilla UI can do, vs what the Preact UI
 * actually does.
 *
 * Both halves of the earlier audit were unreliable and neither said so:
 *
 *   - Grepping the new bundle for endpoint strings finds almost nothing, because
 *     the endpoints are assembled at runtime ('/settings/' + name + '?set=' +
 *     ...), so minified source undercounts them and the report claimed ~19
 *     endpoints against the old UI's 33.
 *   - Comparing element IDs finds the same thing: the new UI names its controls
 *     differently, so a missing ID is not a missing feature.
 *
 * So: load the new UI, click every control, and record the requests the phone
 * actually receives. A feature exists only if some control reaches the endpoint
 * that implements it. Toggles are clicked back so the phone ends as it started.
 */
const { chromium } = require('playwright');
const fs = require('fs');

const OLD = '/tmp/old.html';
const NEW = 'http://192.168.1.184:8080/';
const BACK = /^(bLL|bNight|bMotion|bTorch|bStream)$/;

(async () => {
  const old = fs.readFileSync(OLD, 'utf8');

  // The old UI's endpoint list is trustworthy: unminified, endpoints are
  // literals in its fetch() calls.
  const oldPaths = new Set();
  for (const m of old.matchAll(/fetch\(\s*[`'"]([^`'"]+)/g)) oldPaths.add(m[1].split('?')[0]);
  for (const m of old.matchAll(/['"`](\/[a-z][\w/.-]*)/g)) {
    if (!m[1].includes('<')) oldPaths.add(m[1].split('?')[0]);
  }
  // Transport, not features: the library is inlined into the bundle now, the
  // playlist is mounted at /hls.m3u8, and the favicon is a static file.
  const TRANSPORT = new Set(['/hls.min.js', '/hls/index.m3u8', '/hls.m3u8', '/favicon.ico']);

  const b = await chromium.launch();
  const p = await b.newPage();
  const reqs = [];
  p.on('request', (r) => {
    const u = new URL(r.url());
    if (/seg\d+|init\.mp4|\.(png|ico|jpg)$/.test(u.pathname)) return;
    if (r.method() === 'GET' || r.method() === 'POST') {
      reqs.push(r.method() + ' ' + u.pathname + (u.search || ''));
    }
  });
  const errors = [];
  p.on('pageerror', (e) => errors.push(String(e)));

  await p.goto(NEW, { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(9000);

  // Collect id/label/tag for every control BEFORE clicking anything. Clicking
  // the language switcher reloads the page, which destroys every other element
  // handle -- the first version of this script stopped after one control for
  // exactly that reason and reported the untouched endpoints as "not wired up".
  const inventory = await p.evaluate(() =>
    [
      ...document.querySelectorAll(
        'button, [role=button], .ctl, select, input[type=radio], input[type=range]',
      ),
    ].map((e, i) => ({
      i,
      id: e.id || '',
      tag: e.tagName,
      label: (e.textContent || '').trim().slice(0, 30),
      options: e.tagName === 'SELECT' ? [...e.options].map((o) => o.value).filter(Boolean) : [],
    })),
  );

  const clicked = [];
  // Destructive: these delete files or stop a running recording.
  const skip = /^(clipDelete|clipClear|stopRec|bRec30)$/;
  for (const item of inventory) {
    const { i, id, tag, label, options } = item;
    if (skip.test(id)) {
      clicked.push(`${id} "${label}" POMINIETY (niszczy dane)`);
      continue;
    }
    if (tag === 'A') {
      clicked.push(`${id || '(link)'} "${label}" POMINIETY (nawigacja)`);
      continue;
    }
    // Re-acquire the handle per control: a reload between controls invalidates
    // the earlier ones, and a stale handle throws instead of reporting.
    const sel = () =>
      p.$$('button, [role=button], .ctl, select, input[type=radio], input[type=range]');
    const c = (await sel())[i];
    if (!c) {
      clicked.push(`${id} "${label}" NIEZNALAZIONY po reloadzie`);
      continue;
    }
    const before = reqs.length;

    // Radios and sliders are how the audio codec and every numeric setting are
    // reached -- /audio/codec sent nothing because its control is a radio group,
    // not a select, and the audit only ever clicked buttons. A radio that is
    // already checked fires no change, so the first unchecked one is chosen.
    if (tag === 'INPUT') {
      const type = await c.evaluate((e) => e.type);
      if (type === 'radio') {
        const state = await c.evaluate((e) => ({ checked: e.checked, val: e.value }));
        if (state.checked) {
          clicked.push(`${id || '(radio)'} ${state.val} POMINIETY (już zaznaczony)`);
          continue;
        }
        try {
          await c.check({ timeout: 2500 });
        } catch (e) {
          clicked.push(`${id || '(radio)'} ${state.val} nie udalo sie zaznaczyc`);
          continue;
        }
        await p.waitForTimeout(600);
        const firedR = reqs.slice(before).filter((r) => !/status\.json/.test(r));
        clicked.push(
          `${id || '(radio)'} ${state.val} -> ` + (firedR.length ? firedR.join(' ') : 'brak HTTP'),
        );
        continue;
      }
      if (type === 'range') {
        const meta = await c.evaluate((e) => ({ min: +e.min, max: +e.max, now: +e.value }));
        // Move it a step rather than to the extreme: the extremes are valid but
        // an unrelated setting would then be left at a value nobody chose.
        const target = meta.now + 1 <= meta.max ? meta.now + 1 : meta.now - 1;
        try {
          await c.evaluate((e, v) => {
            e.value = v;
            e.dispatchEvent(new Event('input', { bubbles: true }));
            e.dispatchEvent(new Event('change', { bubbles: true }));
          }, target);
        } catch (e) {
          clicked.push(`${id || '(range)'} nie udalo sie ustawic`);
          continue;
        }
        await p.waitForTimeout(600);
        const firedRg = reqs.slice(before).filter((r) => !/status\.json/.test(r));
        clicked.push(
          `${id || '(range)'} ${meta.now}->${target} -> ` +
            (firedRg.length ? firedRg.join(' ') : 'brak HTTP'),
        );
        continue;
      }
      clicked.push(`${id || '(input ' + type + ')'} POMINIETY (typ ${type})`);
      continue;
    }

    if (tag === 'SELECT') {
      // A <select> never sends anything on its own -- the endpoints behind
      // /audio/codec, /settings/effect and /settings/quality are all reached
      // through a change event, so clicking one reported "brak HTTP" and the
      // three settings looked unwired. Pick a real option and fire change.
      if (!options.length) {
        clicked.push(`${id} "${label}" POMINIETY (brak opcji)`);
        continue;
      }
      const pick = options[options.length - 1];
      try {
        await p.selectOption('#' + CSS.escape(id) || 'select', pick).catch(async () => {
          await c.selectOption(pick);
        });
      } catch (e) {
        try {
          await c.selectOption(pick);
        } catch (e2) {
          clicked.push(`${id} "${label}" nie udalo sie wybrac opcji`);
          continue;
        }
      }
      await p.waitForTimeout(600);
      const firedS = reqs.slice(before).filter((r) => !/status\.json/.test(r));
      clicked.push(
        `${id} "${label}" [wybrano ${pick}] -> ` + (firedS.length ? firedS.join(' ') : 'brak HTTP'),
      );
      continue;
    }

    try {
      await c.click({ timeout: 2500 });
    } catch (e) {
      clicked.push(`${id} "${label}" kliknięcie nieudane`);
      continue;
    }
    await p.waitForTimeout(500);
    const fired = reqs.slice(before).filter((r) => !/status\.json/.test(r));
    clicked.push(
      `${id || '(brak id)'} "${label}" -> ${fired.length ? fired.join(' ') : 'brak HTTP'}`,
    );
    if (id && BACK.test(id)) {
      const again = (await p.$$('button, [role=button], .ctl'))[i];
      if (again) {
        try {
          await again.click({ timeout: 2500 });
        } catch (e) {}
      }
      await p.waitForTimeout(400);
    }
  }

  const reached = new Set(reqs.map((r) => r.replace(/^\w+ /, '').split('?')[0]));

  console.log(`=== KONTROLE W NOWYM UI (${inventory.length}) ===`);
  clicked.forEach((c) => console.log('  ' + c));

  console.log('\n=== ENDPOINTY STAREGO UI, KTORYCH ZADNA KONTROLA NIE ODWOLALA ===');
  const missing = [...oldPaths]
    .filter((u) => u.length > 1 && !TRANSPORT.has(u) && !reached.has(u))
    .sort();
  console.log(missing.length ? '  ' + missing.join('\n  ') : '  (brak)');

  console.log('\n=== ENDPOINTY DOTKNIETE PRZEZ UI ===');
  console.log('  ' + [...reached].sort().join('  '));

  console.log('\nJS errors: ' + (errors.length ? errors.join(' | ') : 'brak'));
  await b.close();
})();

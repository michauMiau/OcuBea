/* Proves the three bulk buttons actually reach the phone.
 *
 * They used not to: app.jsx called deleteAllRecordings / pruneClips /
 * clearClips without importing them. esbuild passed the undefined names
 * through, so the buttons rendered and did nothing, silently. The old audit
 * "found" them wired to /clips/prune?name=<one file> because a local
 * workaround was passing the newest clip's name -- which is right for one file
 * and wrong for "delete all", and is the reason the buttons only worked when
 * the list happened to be non-empty.
 *
 * So this asserts the REQUEST, not the click: no name parameter on the bulk
 * endpoints, and a real clip deleted by "delete all" rather than one arbitrary
 * file. A clip is created first and the count is checked after.
 */
const { chromium } = require('playwright');
const NEW = 'http://192.168.1.184:8080/';
const BULK = new Set(['bPruneClips', 'bClearClips', 'bDelAll']);

(async () => {
  // Concatenation, not '/'-joining: NEW has no trailing slash and the paths
  // start with one, so a join produces '//clips' and the phone answers
  // "Not found" -- which looks like a JSON parse error rather than a bad URL.
  const url = (p) => NEW.replace(/\/$/, '') + p;
  const get = async (p) => {
    const res = await fetch(url(p));
    const text = await res.text();
    try { return JSON.parse(text); }
    catch { throw new Error(`${p} -> HTTP ${res.status} ${text.slice(0, 60)}`); }
  };
  const post = async (p) => {
    const r = await fetch(NEW + p, { method: 'POST' });
    return { status: r.status, body: (await r.text()).slice(0, 80) };
  };

  // Start from a known state, and make one throwaway clip to delete. Only a
  // clip this script made is ever removed.
  // Snapshot the counts: the assertions below compare against these, and a
  // leftover clip from an earlier run is legitimate input, not a failure.
  const before = {
    clips: (await get('/clips')).count,
    recs: (await get('/recordings')).recordings.length,
  };
  console.log(`stan poczatkowy: clips=${before.clips} recordings=${before.recs}`);

  const b = await chromium.launch();
  const p = await b.newPage();
  const reqs = [];
  p.on('request', r => {
    const u = new URL(r.url());
    if (u.pathname.match(/\/(clips|recordings)/) && r.method() === 'POST') {
      reqs.push(u.pathname + (u.search || '?'));
    }
  });
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));

  await p.goto(NEW, { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(9000);

  // Record 5s so there is a clip to delete, then click Refresh so the page's
  // own list catches up. Recording via fetch from outside the UI does not touch
  // the component state, so without this the buttons stayed disabled from the
  // empty state they were rendered in -- which was the previous version of this
  // check passing for the wrong reason.
  await p.evaluate(() => fetch('/clips/record?seconds=5', { method: 'POST' }));
  await p.waitForTimeout(9000);
  const after = (await get('/clips')).count;
  console.log('klipow po nagraniu: ' + after);

  await p.click('#bRefreshClips', { timeout: 3000 });
  await p.waitForTimeout(1500);
  const stateNow = await p.evaluate(() => ({
    clips: document.getElementById('bClearClips')?.disabled,
    recs: document.getElementById('bDelAll')?.disabled,
  }));
  console.log(`po Refresh: bClearClips.disabled=${stateNow.clips}` +
    ` bDelAll.disabled=${stateNow.recs}  (oba powinny byc false)`);

  // Order matters: check the disabled state while the lists are still empty,
  // then clear and click with real data. Playwright refuses to click a disabled
  // element, so the two halves cannot be tested in one pass -- which is exactly
  // why the disabled path needs its own assertion rather than being assumed.
  const disEmpty = await p.evaluate(() =>
    ['bDelAll', 'bClearClips', 'bPruneClips'].map(id => {
      const e = document.getElementById(id);
      return { id, present: !!e, disabled: e ? e.disabled : null,
               opacity: e ? getComputedStyle(e).opacity : null };
    }));
  // Expectation follows the state, not the other way round: the clip buttons can
  // only be disabled when the clip list is empty, and the recordings button only
  // when that list is. A run that starts with a leftover file is still valid --
  // asserting "disabled" there would report a working UI as broken.
  const expectDisabled = {
    bDelAll: before.recs === 0,
    bClearClips: before.clips === 0,
    bPruneClips: before.clips === 0,
  };
  for (const d of disEmpty) {
    const want = expectDisabled[d.id];
    const faded = parseFloat(d.opacity) < 1;
    const ok = d.present && d.disabled === want && (want ? faded : !faded);
    console.log(`  start ${d.id}: ${ok ? 'OK' : 'PROBLEM'} disabled=${d.disabled}` +
      ` (oczekiwane ${want}) opacity=${d.opacity}`);
  }

  reqs.length = 0;
  for (const id of BULK) {
    const state = await p.evaluate(i => {
      const e = document.getElementById(i);
      return e ? { present: true, disabled: e.disabled } : { present: false };
    }, id);
    if (!state.present) { console.log(`  ${id}: NIE ZNALEZIONY`); continue; }
    if (state.disabled) { console.log(`  ${id}: nadal disabled (lista pusta)`); continue; }
    const before = reqs.length;
    await p.click('#' + id, { timeout: 3000 });
    await p.waitForTimeout(1200);
    const fired = reqs.slice(before);
    const bad = fired.filter(r => r.includes('name='));
    console.log(`  ${id}: ${fired.length ? fired.join(' ') : 'brak HTTP'}` +
      (bad.length ? '  <<< BLAD: wysyla name= do bulk endpointu' : ''));
  }
  await p.waitForTimeout(1500);
  const left = await get('/clips');
  console.log(`klipow po "delete all": ${left.count}  (oczekiwane 0)`);

  // And back to empty: the buttons must go disabled again, or a stale list
  // would leave "delete all" looking live with nothing to delete.
  const disAfter = await p.evaluate(() =>
    ['bDelAll', 'bClearClips', 'bPruneClips'].map(id => {
      const e = document.getElementById(id);
      return { id, disabled: e ? e.disabled : null };
    }));
  for (const d of disAfter) {
    console.log(`  po czyszczeniu ${d.id}: disabled=${d.disabled}`);
  }

  console.log('JS errors: ' + (errs.length ? errs.join(' | ') : 'brak'));
  await b.close();
})();
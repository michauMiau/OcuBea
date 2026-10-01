/* Clicks each toggle twice and checks the phone's state actually changes.
 *
 * The audit that missed this clicked every control and recorded the HTTP
 * request. All three toggles sent a request and got "ok" -- and night vision
 * still could not be turned off, because the UI sent the literal string
 * "toggle" and the phone reads that as a fixed value:
 *
 *   night_vision      -> setNightVision(value != "off")  -> "toggle" means ON
 *   motion_detection  -> enabled = (value == "on")        -> "toggle" means OFF
 *   ffc               -> if (value == "toggle") invert   -> actually toggles
 *
 * So one toggle could not be switched off and another could not be switched
 * back on. A request-fired check passes all three, which is why this asserts the
 * state in status.json after a click, and clicks TWICE so a toggle that only
 * ever sets one value is caught rather than passing on the first press.
 *
 * The starting state is set through the API and restored at the end.
 */
const { chromium } = require('playwright');
const H = 'http://192.168.1.184:8080';

const post = (p, v) => fetch(H.replace(/\/$/, '') + '/settings/' + p + '?set=' + v,
  { method: 'POST' });
const state = async () => {
  const d = JSON.parse(await (await fetch(H + '/status.json')).text());
  return { night: !!d.night_vision, motion: !!d.motion?.enabled, front: !!d.front_camera };
};

(async () => {
  const start = await state();
  console.log('stan startowy:', JSON.stringify(start));

  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  await p.goto(H, { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(9000);

  // name -> [id, setting, desired value when the button shows ON]
  const CASES = [
    ['nocne widzenie', 'bNight', 'night_vision', true],
    ['ruch', 'bMotion', 'motion_detection', true],
    ['ffc', 'bFlip', 'ffc', null],   // null: the server inverts, any change counts
  ];

  const results = [];
  for (const [label, id, setting, wantOn] of CASES) {
    const before = await state();
    const key = setting === 'night_vision' ? 'night' : setting === 'motion_detection' ? 'motion' : 'front';

    // First press: whatever the button currently shows, pressing must move it.
    await p.click('#' + id, { timeout: 4000 });
    await p.waitForTimeout(2200);
    const afterFirst = await state();

    // Second press: must move back. A toggle pinned to one value fails here.
    await p.click('#' + id, { timeout: 4000 });
    await p.waitForTimeout(2200);
    const afterSecond = await state();

    const movedOnce = afterFirst[key] !== before[key];
    const movedBack = afterSecond[key] !== afterFirst[key];
    // For ffc the direction is the server's business; only movement matters.
    const ok = setting === 'ffc'
      ? (movedOnce && movedBack)
      : (movedOnce && movedBack && afterSecond[key] === before[key]);

    results.push([label, ok, `${before[key]} -> ${afterFirst[key]} -> ${afterSecond[key]}`]);
  }

  console.log('');
  for (const [label, ok, path] of results) {
    console.log(`  ${ok ? 'OK' : 'PROBLEM'} ${label}: ${path}`);
  }

  // Restore.
  await post('night_vision', start.night ? 'on' : 'off');
  await post('motion_detection', start.motion ? 'on' : 'off');
  if (await state() && (await state()).front !== start.front) await post('ffc', 'toggle');
  console.log('stan po przywroceniu:', JSON.stringify(await state()));

  console.log('JS errors: ' + (errs.length ? errs.join(' | ') : 'brak'));
  const failed = results.filter(r => !r[1]).length;
  console.log(failed ? `\n${failed} toggle(s) nie przełącza sie.` : '\nWszystkie przelaczniki dzialaja.');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
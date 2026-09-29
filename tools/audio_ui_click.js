/* Clicks a radio in the live picker and checks the phone kept the choice. */
const { chromium } = require('playwright');
/* Where is the phone? A DHCP lease moves, so the address is found, not assumed.
   Override with PHONE=192.168.1.x if discovery fails. */
async function phoneBase() {
  if (process.env.PHONE) return 'http://' + process.env.PHONE + ':8080';
  // Ask ADB -- the phone is plugged in when these tools run, and its own
  // sensors endpoint reports the address it is actually reachable on.
  const { execFileSync } = require('child_process');
  try {
    const ip = execFileSync(
      'adb', ['shell', 'ip', '-f', 'inet', 'addr', 'show', 'wlan0'],
      { encoding: 'utf8', timeout: 5000 }
    ).match(/inet (\d+\.\d+\.\d+\.\d+)/);
    if (ip) return 'http://' + ip[1] + ':8080';
  } catch (e) { /* no adb, no device -- fall through */ }
  throw new Error(
    'Cannot find the phone. Set PHONE=<ip>, e.g. PHONE=192.168.1.122');
}

(async () => {
  const want = process.argv[2] || 'opus';
  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  const base = await phoneBase();
  console.log('  telefon: ' + base);
  await p.goto(base + '/?lang=pl', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(4000);
  await p.click('#ac_' + want);
  await p.waitForTimeout(3500);
  const ui = await p.evaluate(w => {
    const r = document.querySelector('#ac_' + w);
    return { checked: r ? r.checked : null,
             status: (document.getElementById('sAudioCodec')||{}).textContent,
             note: (document.getElementById('audioNote')||{}).textContent };
  }, want);
  console.log('  kliknieto:      ' + want);
  console.log('  radio checked:  ' + ui.checked);
  console.log('  status w UI:    ' + ui.status);
  const res = await fetch(base + '/status.json').then(r => r.json());
  console.log('  telefon zapisał: ' + res.audio.codec);
  console.log('  zgodne: ' + (res.audio.codec === want && ui.checked ? 'TAK' : 'NIE'));
  if (errs.length) console.log('  BLEDY JS: ' + errs.join(' | '));
  await b.close();
})();

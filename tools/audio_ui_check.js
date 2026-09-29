/* Renders the picker against the live phone and reports what a user would see. */
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
  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  const base = await phoneBase();
  console.log('  telefon: ' + base);
  await p.goto(base + '/?lang=pl', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(4000);
  const out = await p.evaluate(() => {
    const rows = [...document.querySelectorAll('#audioChoices label')];
    return {
      count: rows.length,
      options: rows.map(r => ({
        value: r.querySelector('input').value,
        checked: r.querySelector('input').checked,
        label: r.querySelector('div div').textContent,
        note: (r.querySelectorAll('div div')[1] || {}).textContent || ''
      })),
      statusCodec: (document.getElementById('sAudioCodec') || {}).textContent,
      heading: (document.querySelector('#audioChoices') || {}).closest ? '' : ''
    };
  });
  console.log('  opcji w selektorze: ' + out.count);
  out.options.forEach(o => console.log(
    '    ' + (o.checked ? '[*]' : '[ ]') + ' ' + o.value.padEnd(7) +
    ' ' + o.label + (o.note ? '  -- ' + o.note.slice(0, 52) : '')));
  console.log('  status pokazuje kodek: ' + out.statusCodec);
  if (errs.length) console.log('  BLEDY JS: ' + errs.join(' | '));
  else console.log('  brak bledow JS');
  await b.close();
})();

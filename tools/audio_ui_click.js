/* Clicks a radio in the live picker and checks the phone kept the choice. */
const { chromium } = require('playwright');
(async () => {
  const want = process.argv[2] || 'opus';
  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  await p.goto('http://192.168.1.122:8080/?lang=pl', { waitUntil: 'domcontentloaded' });
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
  const res = await fetch('http://192.168.1.122:8080/status.json').then(r => r.json());
  console.log('  telefon zapisał: ' + res.audio.codec);
  console.log('  zgodne: ' + (res.audio.codec === want && ui.checked ? 'TAK' : 'NIE'));
  if (errs.length) console.log('  BLEDY JS: ' + errs.join(' | '));
  await b.close();
})();

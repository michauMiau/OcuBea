/* Renders the picker against the live phone and reports what a user would see. */
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  await p.goto('http://192.168.1.122:8080/?lang=pl', { waitUntil: 'domcontentloaded' });
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

/* Checks the Polish strings.xml against the English one.
 *
 * Android falls back to values/ for any name missing from values-pl/, so a
 * missing translation is not a crash — it is a Polish user reading English,
 * silently. That is exactly the kind of bug that survives review, so the
 * check is mechanical: same names, same placeholders, no duplicates.
 *
 * Also flags a translation that is byte-identical to the English. Some of
 * those are correct — app_name, onvif_value, addr_web_ui_value, a bare "Port"
 * next to a number — so they are listed with the reason. A name that is not on
 * that list and has the same text in both files is reported, because the odds
 * are that nobody translated it.
 *
 *   node tools/strings_verify.js
 *
 * Exits non-zero on a missing name, a bad placeholder or an unlisted
 * identical string.
 */
const fs = require('fs');
const path = require('path');

const RES = path.join(__dirname, '..', 'app', 'src', 'main', 'res');
const EN = path.join(RES, 'values', 'strings.xml');
const PL = path.join(RES, 'values-pl', 'strings.xml');

/* Same in both languages on purpose: product and protocol names, templates
 * whose value is a hostname, and short technical labels where the Polish word
 * would be longer and no clearer. */
const SAME_ON_PURPOSE = {
  app_name: 'product name',
  recording_badge: '● REC is a status light, not a word',
  bitrate: 'standard term in the settings next to it',
  lbl_port: 'sits next to a number field',
  fps: 'unit',
  addr_web_ui: 'label for a value that is a URL',
  addr_audio: 'label for a value that is a URL',
  addr_web_ui_value: 'the value itself is a URL',
  addr_audio_value: 'the value itself is a URL',
  onvif_value: 'the value itself is a URL',
};

function parse(file) {
  const src = fs.readFileSync(file, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const out = new Map();
  for (const m of src.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    out.set(m[1], m[2].trim());
  }
  return out;
}

const en = parse(EN);
const pl = parse(PL);
const problems = [];

const dupes = (list) => {
  const src = fs.readFileSync(list, 'utf8');
  const names = [...src.matchAll(/<string name="([^"]+)"/g)].map((m) => m[1]);
  return [...new Set(names.filter((n, i) => names.indexOf(n) !== i))];
};

for (const [label, file] of [
  ['EN', EN],
  ['PL', PL],
]) {
  const d = dupes(file);
  if (d.length) problems.push(label + ' duplikaty nazw: ' + d.join(', '));
}

for (const name of en.keys()) {
  if (!pl.has(name)) problems.push('brak polskiego: ' + name);
}
for (const name of pl.keys()) {
  if (!en.has(name)) problems.push('polski bez angielskiego (nieuzywane?): ' + name);
}

/* %1$s and %d have to survive translation. A Polish sentence that reorders
 * words still needs the same argument indices, and a dropped %s shows up at
 * runtime as a literal "%s" on a button. */
const fmt = /%(?:\d+\$)?[sdfxn]/g;
for (const [name, enText] of en) {
  const plText = pl.get(name);
  if (plText === undefined) continue;
  const a = (enText.match(fmt) || []).sort().join(' ');
  const b = (plText.match(fmt) || []).sort().join(' ');
  if (a !== b) problems.push('placeholdery: ' + name + ' EN=[' + a + '] PL=[' + b + ']');
}

for (const [name, enText] of en) {
  const plText = pl.get(name);
  if (plText === undefined) continue;
  if (plText === enText && !SAME_ON_PURPOSE[name]) {
    problems.push('niezetlumaczone (dodaj do SAME_ON_PURPOSE jesli celowo): ' + name);
  }
}

console.log('=== EN: ' + en.size + ' napisow, PL: ' + pl.size + ' ===');
console.log('--- problemy: ' + problems.length + ' ---');
problems.forEach((p) => console.log('  ! ' + p));
if (problems.length) {
  console.log('\nPopraw values/values-pl/strings.xml.');
  process.exit(1);
}
console.log('Polskie napisy kompletne.');
process.exit(0);

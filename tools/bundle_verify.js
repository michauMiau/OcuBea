#!/usr/bin/env node
/* Fail when the committed WebUI bundle does not match what src/ would build.
 *
 * Three of the WebUI gates -- the translation, layout and interaction checks --
 * do not read src/. They open app/src/main/assets/index.html, the single
 * self-contained file that actually ships inside the APK, and render it in
 * Chromium. That is deliberate: testing the source would test something the
 * phone never loads.
 *
 * The cost is that the bundle can drift, and nothing was watching. Gradle does
 * not build it -- nothing in app/build.gradle.kts invokes node or build.mjs --
 * so the file is committed and edited by hand, and build.mjs has to be run
 * deliberately or the APK ships whatever was in the repository.
 *
 * Measured here: the committed bundle was last rebuilt at 5b1c2fc. Since then
 * src/ has gained 1452 lines across app.jsx, i18n.js, state.js and ui.css. Every
 * WebUI gate was green the whole time, against a bundle that no longer
 * corresponded to the source it was written to protect. The APK built at 18:57
 * carried buildId d7fa31ed while src/ was at 2d2a0850.
 *
 * A gate that only sees the artefact cannot tell a stale artefact from a correct
 * one. This one rebuilds and compares, so the answer is not a matter of
 * discipline:
 *
 *   node tools/bundle_verify.js          check only
 *   node tools/bundle_verify.js --write  rebuild and save
 *
 * Exits non-zero when a rebuild differs from what is committed.
 */

const { execFileSync } = require('child_process');
const { readFileSync, writeFileSync } = require('fs');
const { join } = require('path');

const root = join(__dirname, '..');
const bundle = join(root, 'app', 'src', 'main', 'assets', 'index.html');
const write = process.argv.includes('--write');

/* build.mjs writes the bundle in place and prints its size, so save a copy
 * first, build, compare, and put the original back unless asked to keep it. */
const committed = readFileSync(bundle);

const out = execFileSync('node', [join(root, 'build.mjs')], {
  cwd: root,
  encoding: 'utf8',
});
const rebuilt = readFileSync(bundle);

if (committed.equals(rebuilt)) {
  process.stdout.write('  bundle: zgodny z src/\n');
  process.exit(0);
}

if (write) {
  process.stdout.write(
    `  bundle: zapisany na nowo (${committed.length} -> ${rebuilt.length} B)\n${out}`,
  );
  process.exit(0);
}

/* Report what differs in terms a person can act on. Byte counts alone make a
 * whitespace-only difference look like a rewrite, and the usual cause here is a
 * stale file, not a broken build. */
const commitLines = committed.toString('utf8').split('\n');
const rebuildLines = rebuilt.toString('utf8').split('\n');
process.stdout.write(
  `  BRAK: app/src/main/assets/index.html nie odpowiada src/.\n` +
    `    w repo: ${committed.length} B, po przebudowaniu: ${rebuilt.length} B\n` +
    `    linie: ${commitLines.length} -> ${rebuildLines.length}\n` +
    `    Uruchom: node tools/bundle_verify.js --write\n`,
);

let shown = 0;
for (let i = 0; i < Math.max(commitLines.length, rebuildLines.length) && shown < 5; i++) {
  if (commitLines[i] !== rebuildLines[i]) {
    process.stdout.write(
      `    ${i + 1}: w repo  ${String(commitLines[i]).slice(0, 60)}\n` +
        `    ${i + 1}: nowy    ${String(rebuildLines[i]).slice(0, 60)}\n`,
    );
    shown++;
  }
}
process.exit(1);

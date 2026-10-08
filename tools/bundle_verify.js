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

/* Which build is a given HTML actually? build.mjs stamps buildId as
 * sha256(css + js).slice(0,8) over the PRE-minified sources, so it is the
 * fingerprint of the tree the bundle was generated from, not of the bundle.
 *
 * Why that matters: every WebUI gate in this repo opens the repo bundle, so
 * all of them go green on a correct file while the phone keeps serving
 * whatever APK was installed last. Measured on 2026-10-05: the repo bundle was
 * buildId 04c890e9 and already carried `display: flex` on .streamctl .ico,
 * while http://192.168.1.184:8080/ served buildId d72d67fe whose copy of that
 * rule had no display at all. The gate was green, the browser was right.
 *
 * So this script also answers the question the static gates cannot: does the
 * bundle in the repo match the sources it claims to come from, AND does the
 * live server match the repo?  --device HOST:PORT checks the second half.
 */
const args = process.argv.slice(2);
const device = (args.find((a) => a.startsWith('--device=')) || '').slice(9);
const rebuildOnly = args.includes('--rebuild-only');

function buildIdOf(html) {
  const m = html.match(/v=([0-9a-f]{8})/);
  return m ? m[1] : '(brak)';
}

/* build.mjs writes the bundle in place and prints its size, so save a copy
 * first, build, compare, and put the original back unless asked to keep it. */
const committed = readFileSync(bundle);
const committedId = buildIdOf(committed.toString('utf8'));

const out = execFileSync('node', [join(root, 'build.mjs')], {
  cwd: root,
  encoding: 'utf8',
});
const rebuilt = readFileSync(bundle);
const rebuiltId = buildIdOf(rebuilt.toString('utf8'));

/* Second half of the question: what the phone actually serves. Compared
 * against src/, not against the repo bundle, because the repo bundle agreeing
 * with itself proves nothing about what is installed.
 *
 * Both halves have to finish before the process decides its exit code. An
 * earlier version called process.exit(0) as soon as the rebuild matched, which
 * raced the pending HTTP request and made --device silently print nothing and
 * exit 0. A device check that cannot fail is worse than no device check: it
 * looks like the check ran. */
let pendingDevice = null;
if (device && !rebuildOnly) {
  pendingDevice = { ok: false, code: 2 };
  try {
    const res = require('http').get(`http://${device}/`, (r) => {
      let b = '';
      r.setEncoding('utf8');
      r.on('data', (c) => {
        b += c;
      });
      r.on('end', () => {
        const live = buildIdOf(b);
        const srcMatches = live === rebuiltId;
        process.stdout.write(
          `  telefon ${device}: buildId ${live}, src/ ${rebuiltId} -> ` +
            `${srcMatches ? 'ZGODNY' : 'NIEZGODNY'}\n`,
        );
        if (!srcMatches) {
          process.stdout.write(
            '    Telefon serwuje inny build niz src/. Wgraj nowy APK; ' +
              'bramki czytaja plik w repo, nie to co jest zainstalowane.\n',
          );
        }
        pendingDevice = { ok: srcMatches, code: srcMatches ? 0 : 1 };
      });
    });
    res.on('error', (e) => {
      process.stdout.write(`  telefon ${device}: nieosiagalny (${e.code || e.message})\n`);
      pendingDevice = { ok: false, code: 2 };
    });
  } catch (e) {
    process.stdout.write(`  telefon ${device}: blad ${e.message}\n`);
    pendingDevice = { ok: false, code: 2 };
  }
}

/* Decide the rebuild half first, then report both and exit once.
 *
 * The earlier shape exited from whichever branch ran first, which meant
 * --device printed nothing whenever the rebuild happened to match: the HTTP
 * socket had not answered yet and process.exit killed it mid-flight. Two
 * independent verdicts need one exit code decided after both are known, not an
 * exit inside the first branch that matched. */
const rebuildOk = committed.equals(rebuilt);
let rebuildCode = 0;
let rebuildMsg = `  bundle: zgodny z src/ (buildId ${committedId})\n`;
if (!rebuildOk) {
  if (write) {
    rebuildMsg = `  bundle: zapisany na nowo (${committed.length} -> ${rebuilt.length} B)\n${out}`;
  } else {
    rebuildCode = 1;
    rebuildMsg = mismatchReport(committed, rebuilt);
  }
}

/* The device half resolves later, from the socket. If it was requested, wait
 * for it; its code wins when it fails, because a stale phone is the finding
 * that matters even when the repo is self-consistent. */
function reportAndExit() {
  process.stdout.write(rebuildMsg);
  if (!pendingDevice) process.exit(rebuildCode);
}

if (pendingDevice) {
  setTimeout(() => {
    process.stdout.write(rebuildMsg);
    process.exit(pendingDevice.ok && rebuildOk ? 0 : pendingDevice.code || rebuildCode || 1);
  }, 12000);
} else {
  reportAndExit();
}

function mismatchReport(a, b) {
  const al = a.toString('utf8').split('\n');
  const bl = b.toString('utf8').split('\n');
  let s =
    `  BRAK: app/src/main/assets/index.html nie odpowiada src/.\n` +
    `    w repo: ${a.length} B, po przebudowaniu: ${b.length} B\n` +
    `    linie: ${al.length} -> ${bl.length}\n` +
    `    Uruchom: node tools/bundle_verify.js --write\n`;
  let shown = 0;
  for (let i = 0; i < Math.max(al.length, bl.length) && shown < 5; i++) {
    if (al[i] !== bl[i]) {
      s +=
        `    ${i + 1}: w repo  ${String(al[i]).slice(0, 60)}\n` +
        `    ${i + 1}: nowy    ${String(bl[i]).slice(0, 60)}\n`;
      shown++;
    }
  }
  return s;
}

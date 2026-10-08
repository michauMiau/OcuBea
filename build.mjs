// OcuBea WebUI build.
//
// Produces app/src/main/assets/index.html as ONE self-contained file: the CSS
// and the bundled JS are inlined, so the phone serves a single asset and there
// is no separate <script src> to fetch. That matters because a separate script
// file only works if the browser executes document scripts, which not every
// engine does (Camoufox 152 headless does not, measured 2026-09-30).
//
//   node build.mjs          build once
//   node build.mjs --watch  rebuild on change
import { build, context } from 'esbuild';
import { readFileSync, writeFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = dirname(fileURLToPath(import.meta.url));
const outFile = resolve(root, 'app/src/main/assets/index.html');
const cssFile = resolve(root, 'src/ui.css');

// A short id of the content actually shipped. Used as the favicon's query
// string so a changed build asks for a URL the browser has never cached,
// rather than relying on it noticing that the bytes behind one URL moved.
const buildId = (css, js) => createHash('sha256').update(css).update(js).digest('hex').slice(0, 8);

const shell = (css, js, v) => `<!DOCTYPE html>
<html lang="en" data-default-lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="theme-color" content="#0d1117">
<meta name="ocubea-build" content="${v}">
<title>OcuBea</title>
<link rel="icon" type="image/png" href="/favicon.ico">
<!-- No query string on the icon, deliberately. A build-id query here means a
     browser treats every build as a brand new URL, so the favicon can never be
     served from cache and the entry grows one dead URL per build. Changing the
     icon then means editing the file, which is the point: the icon is part of
     the app, not a build artefact, and it should look the same on every
     install. Cache-Control on the response is what bounds a stale copy. -->
<style>
${css}
</style>
</head>
<body>
<div id="root"></div>
<script>
${js}
</script>
</body>
</html>
`;

const options = {
  entryPoints: [resolve(root, 'src/app.jsx')],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'es2018',
  // The phone is Android 6.0 (API 23); es2018 is well inside its support.
  jsx: 'automatic',
  jsxImportSource: 'preact',
  loader: { '.jsx': 'jsx' },
  write: false,
  logLevel: 'info',
};

async function emit(result) {
  const js = result.outputFiles[0].text;
  const css = readFileSync(cssFile, 'utf8');
  writeFileSync(outFile, shell(css, js, buildId(css, js)));
  const kb = (statSync(outFile).size / 1024).toFixed(1);
  console.log(`index.html  ${kb} kB  (js ${(js.length / 1024).toFixed(1)} kB)`);
}

if (process.argv.includes('--watch')) {
  const ctx = await context(options);
  await ctx.watch();
  // Rebuild on CSS edits too, which esbuild does not track for a plain read.
  const { watch: watchFs } = await import('node:fs');
  let cssTimer = null;
  watchFs(cssFile, () => {
    clearTimeout(cssTimer);
    cssTimer = setTimeout(async () => {
      const js = (await ctx.rebuild()).outputFiles[0].text;
      const c2 = readFileSync(cssFile, 'utf8');
      writeFileSync(outFile, shell(c2, js, buildId(c2, js)));
    }, 60);
  });
  console.log('watching src/ …');
} else {
  emit(await build(options));
}

// OcuBea WebUI — entry point.
//
// Preact + htm, bundled by esbuild into the single index.html the phone
// serves. No JSX transform, so the source stays readable as tagged templates.

import { createElement, render } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import htm from 'htm';
import Hls from 'hls.js';

import { api, bytes, get, setSetting, ptz, deleteRecording, setToken, getToken, t, uptime, LANG as T_LANG } from './state.js';
import { hlsMessage } from './hlsmsg.js';

// A visible, on-page log. Silent failures are how a black rectangle survives a
// reload, so anything worth knowing lands here as well as in the console.
const log = (msg) => {
  console.log(msg);
  let el = document.getElementById('ocubeaLog');
  if (!el) {
    el = document.createElement('pre');
    el.id = 'ocubeaLog';
    el.style.cssText = 'margin:8px 16px;padding:8px;background:#161b22;border:1px solid #30363d;' +
      'border-radius:8px;color:var(--dim,#8b949e);font:11px/1.4 monospace;white-space:pre-wrap';
    document.body.appendChild(el);
  }
  const line = new Date().toISOString().slice(11, 19) + '  ' + msg;
  el.textContent = (el.textContent + '\n' + line).split('\n').slice(-14).join('\n');
};

// htm.bind must receive a real component context (Preact's), not null: the
// bound template resolves tags through it. Binding null gives
// "TypeError: r.apply is not a function" on the first render and nothing mounts.
const html = htm.bind(/** @type {any} */ (createElement));

// ── small building blocks ───────────────────────────────────────────────────
const Row = ({ label, children }) => html`
  <div class="row"><span>${label}</span><div class="ctl">${children}</div></div>
`;

const Slider = ({ value, min = 0, max = 100, step = 1, onInput, label }) => html`
  <input type="range" min=${min} max=${max} step=${step} value=${value}
    aria-label=${label} onInput=${(e) => onInput(+e.target.value)} />
`;

const Toggle = ({ on, onChange, label, id }) => html`
  <button id=${id} class=${'tgl' + (on ? ' on' : '')} onClick=${() => onChange(!on)}
    aria-label=${label} aria-pressed=${on}></button>
`;

// ── live video ──────────────────────────────────────────────────────────────
// Owns the <img> (MJPEG) and the <video> (HLS); never both at once.
//
// hls.js used to be loaded as a separate <script src="/hls.min.js">. A 414 kB
// file that returns 200 can still leave window.Hls undefined when the engine
// does not run document scripts (Camoufox 152 headless does not, and it fires
// onload anyway, so the failure looks like a file problem). Bundled inline,
// there is no second file to load and nothing to go wrong.
function Stream({ mode, running, lowLatency }) {
  const imgRef = useRef(null);
  const vidRef = useRef(null);
  const hlsRef = useRef(null);
  const [hlsError, setHlsError] = useState(null);

  // A demuxer that cannot parse our fMP4 init segment cannot be talked out of
  // it, so HLS will never produce a picture for this engine. Show MJPEG
  // instead — the one stream every engine renders — and say why, rather than
  // leaving a black rectangle with a raw hls.js error code on it.
  const fellBack = hlsError === 'hls_unplayable_mjpeg';
  const useMjpeg = mode === 'mjpeg' || fellBack;

  useEffect(() => {
    const img = imgRef.current;
    const vid = vidRef.current;
    if (!img || !vid) return;

    // An async IIFE, not an async callback: the warm-up below has to be awaited
    // *between* creating the Hls instance and handing it the source, and a
    // useEffect callback cannot be async (it would return a Promise where a
    // cleanup function is expected). `cancelled` is how the cleanup below stops
    // an in-flight warm-up from attaching a source to a destroyed instance.
    let cancelled = false;
    let fallbackTimer = null;

    const run = async () => {
    // MJPEG: a plain <img> fed by a multipart endpoint. Stop it by dropping src.
    // The cache-buster matters: reconnecting to the same URL lets the browser
    // reuse a remembered response and the viewer gets a frozen first frame.
    //
    // useMjpeg, not mode === 'mjpeg': it is also true once the HLS fallback has
    // fired, and this branch is what puts a picture up in that case.
    if (useMjpeg && running) {
      img.src = '/video?nocache=' + Date.now();
      return () => { img.src = ''; };
    }
    img.src = '';

    if (mode !== 'hls' || !running) return;


    if (hlsRef.current) { hlsRef.current.destroy(); hlsRef.current = null; }
    setHlsError(null);

    // hls.js 1.5.x raises mediaSourceRequiresReset when attachMedia() runs on a
    // <video> that still carries a src from a previous session, which is exactly
    // what happens when this effect re-runs on a mode or latency change. Clear
    // it and stop playback so attachMedia starts from a clean element.
    vid.removeAttribute('src');
    vid.load();

    if (!Hls.isSupported()) {
      // Old Android: H.264 in MSE may be missing. Say so instead of hanging.
      if (vid.canPlayType('application/vnd.apple.mpegurl')) vid.src = '/hls.m3u8';
      else setHlsError('hls_unsupported_mse');
      return;
    }


    const hls = new Hls({ lowLatencyMode: lowLatency, enableWorker: true });
    hlsRef.current = hls;
    hls.on(Hls.Events.ERROR, (_e, d) => {
      // A live stream drops fragments and reloads its playlist constantly; those
      // are recoverable and hls.js retries them itself. Only a fatal error needs
      // action, and the documented recovery is to rebuild the MediaSource.
      if (!d.fatal) return;
      // MEDIA_SOURCE_REQUIRES_RESET, not MEDIA_SOURCE_RESET: the shorter name
      // does not exist on ErrorDetails, so the comparison was always false and
      // this branch never ran. The value the event carries is the
      // "mediaSourceRequiresReset" string either way.
      if (d.details === Hls.ErrorDetails.MEDIA_SOURCE_REQUIRES_RESET ||
          d.details === Hls.ErrorDetails.OTHER_MEDIA_ERROR) {
        log('HLS: recreating MediaSource after ' + d.details);
        setHlsError(null);
        hls.recoverMediaError();
        return;
      }
      setHlsError(d.details || 'hls_error');
    });
    // The phone starts the H.264 encoder lazily, on the first playlist request,
    // and answers 503 "Encoder warming up — first keyframe not encoded yet"
    // until the first keyframe lands.
    //
    // That 503 has to be absorbed here. hls.js treats it as a fatal manifest
    // error, gives up on the source, and stops requesting -- so a client-side
    // retry loop never sees the 200 that follows a second later. Waiting for the
    // playlist to become available *before* handing the URL to hls.js is the
    // only order in which the stream can start at all on a cold encoder.
    if (!hlsRef.current) setHlsError('hls_warming');
    const ready = await new Promise((resolve) => {
      const deadline = Date.now() + 20000;
      const poll = setInterval(async () => {
        if (cancelled || hlsRef.current !== hls || Date.now() > deadline) {
          clearInterval(poll);
          resolve(false);
          return;
        }
        try {
          const r = await fetch('/hls.m3u8');
          // 200 means an init segment exists. Anything else is still warming.
          if (r.ok) { clearInterval(poll); resolve(true); }
        } catch { /* not up yet */ }
      }, 700);
    });

    if (cancelled || hlsRef.current !== hls) return;
    if (!ready) {
      log('HLS: encoder never produced a keyframe');
      hls.destroy();
      hlsRef.current = null;
      setHlsError('hls_unplayable_mjpeg');
      return;
    }

    hls.loadSource('/hls.m3u8');
    hls.attachMedia(vid);

    // Firefox rejects our fMP4 init segment in MP4Demuxer::Init() on bytes
    // ffmpeg decodes without complaint, and nothing on the phone side can fix
    // that. Switch to the stream that is known to work everywhere rather than
    // leaving the user to discover the workaround.
    fallbackTimer = setTimeout(() => {
      if (hlsRef.current !== hls) return;
      if (vid.readyState > 2 || vid.currentTime > 0) return;
      log('HLS: no frames after 8s, falling back to MJPEG');
      hls.destroy();
      hlsRef.current = null;
      setHlsError('hls_unplayable_mjpeg');
    }, 8000);

    // Autoplay can be refused; a muted+playsinline video is allowed far more often.
    vid.muted = true;
    vid.play().catch(() => {});
    };

    run();

    return () => {
      cancelled = true;
      if (fallbackTimer) clearTimeout(fallbackTimer);
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };
  }, [mode, running, lowLatency, fellBack]);

  return html`
    <div class="stream">
      <img ref=${imgRef} alt="Live stream" style=${useMjpeg ? '' : 'display:none'} />
      <video ref=${vidRef} playsinline muted autoplay
        style=${mode === 'hls' && !fellBack ? '' : 'display:none'}></video>
      ${!running && html`<div class="off">${t('streamOffline')}</div>`}
      ${fellBack && html`<div class="badge">HLS unsupported here — MJPEG</div>`}
      ${hlsError === 'hls_warming' && html`<div class="off">${t('hlsWarming')}</div>`}
      ${hlsError && !fellBack && hlsError !== 'hls_warming' && running &&
        html`<div class="off">${hlsMessage(hlsError)}</div>`}
    </div>
  `;
}

// ── app ─────────────────────────────────────────────────────────────────────
function App() {
  const [status, setStatus] = useState(null);
  const [sensors, setSensors] = useState(null);
  const [online, setOnline] = useState(true);
  const [mode, setMode] = useState(() => localStorage.getItem('ocubea_mode') || 'mjpeg');
  const [lowLatency, setLowLatency] = useState(() => localStorage.getItem('ocubea_ll') === '1');
  const [clips, setClips] = useState([]);
  const [recordings, setRecordings] = useState([]);
  const [flash, setFlash] = useState('');
  const [token, setTokenState] = useState(() => getToken());
  // i18n.js owns the choice; the header just reflects it and flips it.
  const [lang] = useState(T_LANG); // 'pl' | 'en'

  const poll = useCallback(async () => {
    try {
      const s = await get('/status.json');
      setStatus(s);
      setOnline(true);
    } catch {
      // The phone is restarting. Say so rather than leaving stale numbers up
      // that look live.
      setOnline(false);
      return;
    }
    try { setSensors(await get('/sensors.json')); } catch { /* optional */ }
  }, []);

  useEffect(() => {
    poll();
    const h = setInterval(poll, 1000);
    return () => clearInterval(h);
  }, [poll]);

  const loadLists = useCallback(async () => {
    // These answer with { clips: [...] } and { recordings: [...] }, not bare
    // arrays; reading the response itself rendered an empty list with no error.
    try { setClips((await get('/clips')).clips || []); } catch { /* not mounted */ }
    try { setRecordings((await get('/recordings')).recordings || []); } catch { /* none yet */ }
  }, []);

  useEffect(() => { loadLists(); }, [loadLists]);

  // Every action goes through here so a failure is visible instead of silent.
  // fn is a callable (a helper from state.js) rather than a bare path, because
  // the phone takes some parameters in the query string and some in a body, and
  // a wrong guess there is answered with 200 "ok" and no effect at all.
  const act = useCallback(async (fn, ...args) => {
    const after = typeof args[args.length - 1] === 'function' ? args.pop() : null;
    try {
      await fn(...args);
      if (after) await after();
    } catch (e) {
      setFlash(String(e.message || e));
      setTimeout(() => setFlash(''), 4000);
    }
  }, []);

  const setModeBoth = (m) => { setMode(m); localStorage.setItem('ocubea_mode', m); };
  const running = !!(status && status.camera_active);

  const s = status || {};
  const hls = s.hls || {};
  const sensorsDev = sensors && sensors.device ? sensors.device : {};
  // status.json is flat: torch, zoom and front_camera are top-level keys, not
  // members of a "device" object that does not exist. Reading them off a missing
  // object rendered every optics control permanently off.
  const audio = s.audio || {};
  // zoom is { level, max } with max around 3.0, and the server maps anything
  // <= 1 as a step, so it must be sent as an absolute level in that range.
  // Handing it 0-100 made the camera jump to its 3x limit and then stay there.
  const zoom = s.zoom || {};
  const zoomLevel = zoom.level || 1;
  const zoomMax = zoom.max || 3;
  const recording = s.recording || {};

  return html`
    <header>
      <h1>OcuBea</h1>
      <span class=${'pill ' + (running ? 'on' : 'off')}>
        ${running ? '● ' + t('live') : '○ ' + t('stopped')}
      </span>
      <span class="pill">${s.fps || 0} ${t('fps')}</span>
      ${!online && html`<span class="pill off">${t('phoneOffline')}</span>`}
      <button class="lang" title=${t('language')}
        onClick=${() => {
          // lang, not T: T is the resolved translation table, so comparing it
          // to 'pl' was always false and the button always offered EN.
          localStorage.setItem('ocubea_lang', lang === 'pl' ? 'en' : 'pl');
          location.reload();
        }}>${lang.toUpperCase()}</button>
    </header>

    <main>
      <${Stream} mode=${mode} running=${running} lowLatency=${lowLatency} />

      ${flash && html`<div class="flash">${flash}</div>`}

      <section>
        <button class="big" onClick=${() => act(setSetting, 'force_stop', '1', poll)}>
          ${t('stop')}
        </button>
        <button class="big" onClick=${() => act(setSetting, 'force_start', '1', poll)}>
          ${t('start')}
        </button>
        <button class="big" id="bMode" onClick=${() => setModeBoth(mode === 'mjpeg' ? 'hls' : 'mjpeg')}>
          ${t('mode')}: ${mode === 'mjpeg' ? 'MJPEG' : 'HLS'}
        </button>
        <button id="bLL" class=${'big' + (lowLatency ? ' on' : '')}
          onClick=${() => {
            const v = !lowLatency;
            setLowLatency(v);
            localStorage.setItem('ocubea_ll', v ? '1' : '0');
          }}>
          ${t('lowLatency')}: ${lowLatency ? t('on') : t('off')}
        </button>
        <button class="big" onClick=${() => {
          const a = document.createElement('a');
          a.href = '/shot.jpg?t=' + Date.now();
          a.download = 'ocubea-' + Date.now() + '.jpg';
          a.click();
        }}>${t('snapshot')}</button>
      </section>

      <section>
        <h2>${t('status')}</h2>
        <dl>
          <dt>FPS</dt><dd>${s.fps || 0}</dd>
          <dt>${t('encoder')}</dt><dd>${hls.codec || s.encoder || '–'}</dd>
          <dt>${t('segments')}</dt><dd>${hls.active ? hls.segments || 0 : '–'}</dd>
          <dt>${t('resolution')}</dt><dd>${s.resolution || '–'}</dd>
          <dt>${t('uptime')}</dt><dd>${uptime(s.uptime_s || 0)}</dd>
          <dt>${t('dropped')}</dt><dd>${(s.pipeline && s.pipeline.dropped_saturated) || s.dropped || 0}</dd>
        </dl>
      </section>

      <section>
        <h2>${t('optics')}</h2>
        <${Row} label=${t('zoom')}>
          <${Slider} value=${Math.round((zoomLevel - 1) * 100)} max=${Math.round((zoomMax - 1) * 100)} step=${1}
            label=${t('zoom')}
            onInput=${(v) => {
              // The phone reads any value <= 1 as a *step* rather than a level
              // (setZoom(1f + zoom)), so 1.00 itself lands on 2.0x. A hair above
              // 1 is the smallest absolute level it will honour, which is what
              // the bottom of the slider means to a user.
              const level = 1 + (zoomMax - 1) * (v / (Math.round((zoomMax - 1) * 100) || 1));
              act(ptz, { zoom: Math.max(1.01, level).toFixed(2) });
            }} />
          <span class="val">${zoomLevel.toFixed(1)}×</span>
        <//>
        <${Row} label=${t('torch')}>
          <${Toggle} id="bTorch" on=${!!s.torch} label=${t('torch')}
            onChange=${(v) => act(setSetting, 'torch', v ? 'on' : 'off', poll)} />
        <//>
        <${Row} label=${t('flip')}>
          <${Toggle} on=${!!s.front_camera} label=${t('flip')}
            onChange=${() => act(setSetting, 'front_camera', 'toggle', poll)} />
        <//>
        <${Row} label=${t('viewers')}>
          <span class="val">${s.viewers || 0}</span>
        <//>
      </section>

      <section>
        <h2>${t('recordings')}</h2>
        <${Row} label=${t('recording')}>
          <${Toggle} id="bRec" on=${!!recording.enabled} label=${t('recording')}
            onChange=${(v) => act(setSetting, 'recording', v ? 'on' : 'off',
              async () => { await poll(); await loadLists(); })} />
        <//>
        ${recordings.length === 0
          ? html`<p class="dim">${t('noRecordings')}</p>`
          : recordings.map((r) => html`
              <div class="row">
                <span class="name">${r.name}</span>
                <a class="ctl" href=${'/recordings/' + encodeURIComponent(r.name)}>↓</a>
                <button class="ctl" onClick=${() => act(deleteRecording, r.name, loadLists)}>✕</button>
              </div>`)}
      </section>

      <section>
        <h2>${t('clips')}</h2>
        ${clips.length === 0
          ? html`<p class="dim">${t('noClips')}</p>`
          : clips.map((c) => html`
              <div class="row">
                <span class="name">${c.name}</span>
                <a class="ctl" href=${'/clips/' + encodeURIComponent(c.name)}>↓</a>
              </div>`)}
      </section>

      <section>
        <h2>${t('device')}</h2>
        <dl>
          <dt>Model</dt><dd>${sensorsDev.model || '–'}</dd>
          <dt>${t('recording')}</dt>
          <dd>${recording.enabled ? (recording.active ? '● ' + t('on') : t('idle')) : t('off')}</dd>
          <dt>${t('audio')}</dt><dd>${audio.enabled ? audio.codec : t('off')}</dd>
          <dt>${t('battery')}</dt>
          <dd>${sensors && sensors.battery ? sensors.battery.level + '%' : '–'}</dd>
          <dt>${t('storage')}</dt>
          <dd>${sensors && sensors.storage ? bytes(sensors.storage.free_mb * 1048576) : '–'}</dd>
        </dl>
      </section>

      <section>
        <h2>${t('api')}</h2>
        <${Row} label=${t('token')}>
          <input class="text" type="password" value=${token} placeholder="—"
            onInput=${(e) => setTokenState(e.target.value)}
            onChange=${(e) => { setToken(e.target.value); setTokenState(e.target.value); }} />
        <//>
        <p class="dim">${t('tokenHint')}</p>
      </section>
    </main>
  `;
}

render(html`<${App} />`, document.getElementById('root'));

// OcuBea WebUI — entry point.
//
// Preact + htm, bundled by esbuild into the single index.html the phone
// serves. No JSX transform, so the source stays readable as tagged templates.

import { createElement, render } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import htm from 'htm';
import Hls from 'hls.js';

import {
  bytes, get, setSetting, ptz, deleteRecording, setToken, getToken,
  clipOnFile, recordNow, stopClipRecording, clipRecordingState, focus, setAudioCodec,
  hlsProfile, setHlsProfile,
  clearClips, pruneClips, deleteAllRecordings,
  t, uptime, LANG as T_LANG,
} from './state.js';
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

// ── option lists ────────────────────────────────────────────────────────────
// Values are the strings the phone accepts on /settings/<name>?set=…, not
// labels. Every one of these was measured against a live Sony F3311: the
// effect names come from the old UI's select, the resolutions from what
// status.json reports back.
const EFFECTS = [
  ['none', 'effectNone'], ['mono', 'effectMono'], ['negative', 'effectNegative'],
  ['sepia', 'effectSepia'], ['night', 'effectNight'],
];

const QUALITIES = [
  ['0', '320×240 (QVGA)'], ['1', '640×480 (VGA)'],
  ['2', '1280×720 (HD)'], ['3', '1920×1080 (FullHD)'],
];

// status.json reports a resolution as "320x240"; the select sends an index.
// Without this the dropdown always showed the first entry on a phone that was
// actually streaming 1080p.
export const qualityKey = (res) => {
  const map = { '320x240': '0', '640x480': '1', '1280x720': '2', '1920x1080': '3' };
  return map[String(res || '').toLowerCase()] || '0';
};

// Built from what the phone actually reports, not a fixed list. Measured on
// two phones: one answers
//   available: aac=true opus=false amrnb=false flac=true default=aac
// so offering opus there gets {"error":"this device cannot encode opus"}. The
// list is a presentation default; AudioCodecs() below overrides it from
// status.json's "available" string on every poll.
const ALL_CODECS = [
  ['none', 'noAudio'], ['aac', 'audioAac'], ['opus', 'audioOpus'],
  ['amrnb', 'audioAmr'], ['flac', 'audioFlac'], ['wav', 'audioWav'],
];

// "aac=true opus=false amrnb=false flac=true default=aac" -> ids the phone
// said yes to. Unknown ids are kept: a phone that reports a codec this build
// has no label for still gets it offered under its raw name rather than
// silently losing it.
const AudioCodecs = (available) => {
  if (!available) return ALL_CODECS;
  const yes = new Set(
    String(available).split(/\s+/).map((p) => p.split('='))
      .filter((kv) => kv[1] === 'true').map((kv) => kv[0])
  );
  if (!yes.size) return ALL_CODECS;
  const listed = ALL_CODECS.filter(([id]) => id === 'none' || yes.has(id));
  for (const id of yes) {
    if (!listed.some(([k]) => k === id)) listed.push([id, id]);
  }
  return listed;
};

// These two are not in status.json, so the slider shows what was last set.
// They are written on every input event, so a reload always starts from the
// phone's value once the user touches them.
// i18n key suffix per protocol value. Written out rather than derived: a
// derivation that misses renders the raw key, which is visible in the picker and
// invisible in the audit, and these four are the only values that exist.
const ORIENT_LABELS = {
  landscape: 'orientLandscape',
  portrait: 'orientPortrait',
  upsidedown: 'orientUpsidedown',
  upsidedown_portrait: 'orientUpsidePortrait',
};

// pydroid-ipcam's own list, used only as a fallback when status.json carries no
// `avail`. The server is the authority: it validates against the same four, and a
// value it would refuse must not appear as an option here.
const ORIENTATIONS = ['landscape', 'portrait', 'upsidedown', 'upsidedown_portrait'];

const PRE_RECORD_DEFAULT = 2;
const MAX_CLIP_DEFAULT = 300;

// ── clip and recording bulk actions ─────────────────────────────────────────
// These used to be worked around here: prune and clear were called through
// clipOnFile() with the newest clip's name, because a bare POST came back 400
// "invalid clip name" and the buttons had to do something.
//
// The 400 was the wrong signal. clipsClear() and clipsPrune() never read the
// query string -- they iterate ClipStorage.list() and delete by retention rule
// -- so the name was only ever there to get past the check. It worked by
// accident: any list that happened to be non-empty kept these buttons alive,
// and an empty list made them inert. The real helpers live in state.js now,
// next to the rest of the wire layer, called bare.

// ── small building blocks ───────────────────────────────────────────────────
const Row = ({ label, children }) => html`
  <div class="row"><span>${label}</span><div class="ctl">${children}</div></div>
`;

const Slider = ({ value, min = 0, max = 100, step = 1, onInput, label, id }) => html`
  <input id=${id} type="range" min=${min} max=${max} step=${step} value=${value}
    aria-label=${label} onInput=${(e) => onInput(+e.target.value)} />
`;

// /hls/profile answers text, not JSON:
//   "profile=default segment_ms=250 keyframe_sec=0 sync=3 buffer=6"
// so the values are parsed out of it rather than fetched as an object.

// MJPEG compression quality.
//
// The select labelled "quality" in this UI is a resolution list, which collides
// with the API's `quality`, where it means JPEG quality. So the slider is named
// for what it changes rather than reusing either word: it moves compression, and
// it is live on every input event so a drag is felt immediately instead of after
// a release. 40..100 is the range the server actually applies -- below that a
// value is never used, and a slider spanning 0..100 would show numbers the
// encoder ignores.
const JpegQuality = ({ value, onInput }) => html`
  <${Row} label=${t('jpegQuality')}>
    <${Slider} id="jpeg-quality" min=${40} max=${100} step=${1}
      value=${value} label=${t('jpegQuality')} onInput=${onInput} />
    <span class="ctl-val">${value}</span>
  </${Row}>
`;

// Stream orientation, as pydroid-ipcam names it.
//
// These four strings are the library's own list, and the server validates
// against it, so the option values are taken from status.json's `avail` rather
// than hardcoded here: a hand-typed value the server refuses would leave the
// picker showing something the API will not take.
const Orientation = ({ value, options, onChange }) => html`
  <${Row} label=${t('orientation')}>
    <select id="orientation" class="ctl" value=${value} aria-label=${t('orientation')}
      onChange=${(e) => onChange(e.target.value)}>
      ${options.map((o) => html`<option value=${o} selected=${value === o}>${t(ORIENT_LABELS[o] || o)}</option>`)}
    </select>
  </${Row}>
`;

const HlsQuality = () => {
  const [p, setP] = useState(null);
  useEffect(() => {
    let dead = false;
    hlsProfile()
      .then((r) => {
        if (dead) return;
        const text = r.text || '';
        const kv = {};
        text.split(/\s+/).forEach((pair) => {
          const i = pair.indexOf('=');
          if (i > 0) kv[pair.slice(0, i)] = pair.slice(i + 1);
        });
        setP(kv);
      })
      .catch(() => {});
    return () => { dead = true; };
  }, []);
  // Not a <Row>: inside the big-button row a Row nests a second .row, which
  // made the outer row 841px wide inside an 798px parent -- the layout check
  // caught it as an overflow. As a direct flex item it shares the button grid.
  return html`
    <span class="badge" id="bHQ"
      title=${'segment_ms=' + (p && p.segment_ms) + ' keyframe_sec=' + (p && p.keyframe_sec)}>
      ${t('hlsQuality')}: ${p ? p.profile : '–'}
    </span>`;
};

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
// ── live audio ──────────────────────────────────────────────────────────────
// The phone has always produced audio: /audio.aac and /audio.wav carry real
// samples (measured: WAV RMS 304.9, peak 5113), and RTSP carries L16 PCM in
// h264_pcm.sdp. None of it reached the browser, because <img> cannot play a
// sound and the HLS <video> is muted and has no audio track to unmute.
//
// So the audio is a separate element fed by a separate stream, not something
// muxed into the picture. That is also why it works identically in MJPEG and in
// HLS mode: the two are independent, and adding audio to fMP4 would mean
// rewriting segments, not adding a tag.
//
// A bare <audio autoplay> is refused by every current browser unless it is
// muted, and unmuting without a gesture is refused too. So it starts muted,
// the user unmutes with a click, and that click is what satisfies the policy.
// A stream that cannot start is reported rather than left silent: autoplay
// rejection and a 4xx are different faults and the message says which.
function LiveAudio({ codec, enabled, running }) {
  const ref = useRef(null);
  const [on, setOn] = useState(false);
  const [err, setErr] = useState(null);

  // Refuse to open a stream the server is not offering, instead of requesting
  // one and rendering a dead player. `none` is the server's own word for it.
  const have = running && enabled && codec && codec !== 'none';

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (!have) {
      // Dropping src is what actually stops the request; a paused element with
      // a src still holds the connection open on the phone's audio client.
      el.pause();
      el.src = '';
      el.load();
      setErr(null);
      return;
    }
    // The cache-buster is once, not per state change: /audio.aac is a live
    // stream, and re-requesting it restarts the encoder's client list.
    el.src = '/audio.' + codec + '?nocache=' + Date.now();
    el.load();
    // The gesture that matters is the click on the toggle, and by the time this
    // effect runs that gesture is over, so play() is called and its rejection
    // is read. Autoplay policy shows up here as NotAllowedError.
    const p = el.play();
    if (p && p.catch) {
      p.catch((e) => {
        setErr(e && e.name === 'NotAllowedError'
          ? 'audio: the browser blocked autoplay — press the speaker button'
          : 'audio: ' + (e && e.name ? e.name : 'the stream did not start'));
      });
    }
  }, [have, codec]);

  // Keep the element's own state in step with the button, so a stream that
  // died on its own does not leave the button claiming it is on.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (on && el.paused) el.play().catch(() => setOn(false));
    if (!on && !el.paused) el.pause();
  }, [on]);

  return html`
    <div class="audio-row">
      <audio ref=${ref} preload="none"></audio>
      <button id="bAud" class=${'tgl' + (on ? ' on' : '')}
        aria-label=${t('audio')} aria-pressed=${on}
        title=${t('audio')}
        onClick=${() => setOn(!on)}></button>
      <span class="dim" id="audioState">
        ${err ? err : have ? (on ? t('audioOn') : t('audioMuted')) : t('audioOff')}
      </span>
    </div>`;
}

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
      if (cancelled) return;
    // MJPEG: a plain <img> fed by a multipart endpoint. Stop it by dropping src.
    //
    // The cache-buster goes on ONCE, when there is no stream yet. Setting a
    // fresh /video?nocache=<timestamp> for every frame makes each one a
    // separate resource: the browser then has to open, tear down and re-open
    // the connection instead of holding one multipart stream, which is what
    // put a second of lag on MJPEG no matter what the low-latency switch said.
    // The old UI guarded this with `if (live.naturalWidth === 0)`.
    //
    // useMjpeg, not mode === 'mjpeg': it is also true once the HLS fallback has
    // fired, and this branch is what puts a picture up in that case.
    if (useMjpeg && running) {
      if (!img.naturalWidth) img.src = '/video?nocache=' + Date.now();
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


    const hls = new Hls({
      // Latency here is set by the SERVER, not by this flag. /hls/profile
      // answers `segment_ms=250 sync=3 buffer=6` and the playlist carries no
      // EXT-X-PART or EXT-X-SERVER-CONTROL, so this is ordinary HLS: a client
      // cannot start a segment until the muxer closes it, and playback sits at
      // least one buffer behind. lowLatencyMode only lets hls.js skip the extra
      // safety margin it would otherwise keep; it cannot make an ordinary
      // playlist low-latency. Measured on the phone: 4.14s of media on the
      // playlist, produced every 0.17s.
      lowLatencyMode: lowLatency,
      // Keep the buffer shallow. A deep buffer on a live stream is only lag, so
      // these are what actually move the playhead: the size it will grow to,
      // and how far behind live it is allowed to sit.
      maxBufferLength: lowLatency ? 2 : 6,
      backBufferLength: lowLatency ? 4 : 30,
      liveSyncDurationCount: lowLatency ? 2 : 3,
      // Without this hls.js refuses to start at all when the manifest reports
      // no EXT-X-ENDLIST and the first sync point is behind the live edge.
      liveDurationInfinity: true,
      enableWorker: true,
      // The playlist is served without a Vary header, so a stale one can be
      // reused; that alone is a second or more of dead time.
      manifestLoadingMaxRetry: 2,
      manifestLoadingRetryDelay: 200,
      levelLoadingMaxRetry: 4,
      fragLoadingMaxRetry: 6,
    });
    hlsRef.current = hls;
    // Exposed so the page can be measured from outside: `__hls.config` is the
    // only place the effective buffer settings are visible once hls.js has
    // merged them with its own defaults.
    window.__hls = hls;
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
    // The phone starts the H.264 encoder lazily, on the first playlist request.
    // The server now holds that request open until the first keyframe exists
    // (StreamServer.handleHlsPlaylist), so hls.js sees a 200 on its very first
    // manifest load instead of a 503 it would treat as fatal. An earlier
    // client-side retry loop waited for the same thing here and was removed:
    // it duplicated the server's wait and raced it.
    if (!hlsRef.current) setHlsError('hls_warming');

    // Checked after the wait as well, not only at the top of run(): this
    // polls for the playlist, and an unmount during that poll used to carry
    // on and attach a source to an instance the cleanup had already destroyed.
    if (cancelled) return;

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
  // Clip telemetry is polled on its own, separately from the file lists: it
  // changes while a clip is being written and there is no reason to re-read the
  // whole list for that. `dropped` in particular is the only way to see frames
  // the encoder could not keep up with.
  const [clipRec, setClipRec] = useState(null);
  // which clip the <video> preview is showing, if any
  const [clipPreview, setClipPreview] = useState(null);
  const [recordings, setRecordings] = useState([]);
  const [flash, setFlash] = useState('');
  const [token, setTokenState] = useState(() => getToken());
  // i18n.js owns the choice; the header just reflects it and flips it.
  const [lang] = useState(T_LANG); // 'pl' | 'en'
  // Neither value is reported back by status.json, so these are the phone's
  // settings held in the UI. Kept in state (not constants) so the slider shows
  // where it was left after a reload, and sent on every input event.
  const [preRecord, setPreRecord] = useState(PRE_RECORD_DEFAULT);
  const [maxClip, setMaxClip] = useState(MAX_CLIP_DEFAULT);

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
    // Same 1s cadence as the rest. Failures are ignored: a phone with the clip
    // writer disabled may not answer, and that must not take the status poll
    // down with it.
    try { setClipRec(await clipRecordingState()); } catch { /* optional */ }
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
  // status.json nests these under "motion": { enabled, detected, sensitivity }.
  const motion = s.motion || {};

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
      <${LiveAudio} codec=${audio.codec} enabled=${!!audio.enabled} running=${running} />

      ${flash && html`<div class="flash">${flash}</div>`}

      <section>
        <div class="row">
          <button id="bStream" class="big danger primary-weight"
            onClick=${() => act(setSetting, 'force_stop', '1')}>
            ${t('stop')}
          </button>
          <button id="bStart" class="big primary primary-weight"
            onClick=${() => act(setSetting, 'force_start', '1')}>
            ${t('start')}
          </button>
          <button class="big secondary" id="bMode"
            onClick=${() => setModeBoth(mode === 'mjpeg' ? 'hls' : 'mjpeg')}>
            ${t('mode')}: ${mode === 'mjpeg' ? 'MJPEG' : 'HLS'}
          </button>
          <button id="bLL" class=${'big secondary' + (lowLatency ? ' on' : '')}
            onClick=${() => {
              const v = !lowLatency;
              setLowLatency(v);
              localStorage.setItem('ocubea_ll', v ? '1' : '0');
              // The client flag is half the job. The player cannot start a
              // segment before the muxer closes it, so the profile on the phone
              // is what sets the floor: LOW_LATENCY cuts segments to 120ms and
              // sync to 1, DEFAULT is 250ms/3. Measured on the phone, DEFAULT
              // put 4.14s of media on the playlist -- with `lowLatencyMode`
              // alone the button changed nothing an end viewer could see.
              act(setHlsProfile, v ? 'low' : 'default', poll);
            }}>
            ${t('lowLatency')}: ${lowLatency ? t('on') : t('off')}
          </button>
          <${HlsQuality} />
          <button id="bShot" class="big secondary" onClick=${() => {
            const a = document.createElement('a');
            a.href = '/shot.jpg?t=' + Date.now();
            a.download = 'ocubea-' + Date.now() + '.jpg';
            a.click();
          }}>${t('snapshot')}</button>
        </div>
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
            id="zoom"
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
        <${Row} label=${t('autofocus')}>
          <button id="bFocus" class="ctl" onClick=${() => act(focus, 0.5, 0.5)}>
            ${t('autofocus')}
          </button>
        <//>
        <${Row} label=${t('flip')}>
          <!-- ffc is the one endpoint that genuinely implements "toggle": it
               reads the camera state and inverts it (StreamServer.kt:890).
               "front"/"back" are NOT accepted -- the arm checks value == "on",
               so set=front answers "ok" and changes nothing, which is a second
               way of getting a dead-looking control. Verified on the phone.
               The comment is HTML, not /* */ inside the attribute list: htm
               treats that as a child, and the button then gets a non-function
               handler and throws "e is not a function" on click. -->
          <${Toggle} id="bFlip" on=${!!s.front_camera} label=${t('flip')}
            onChange=${() => act(setSetting, 'ffc', 'toggle', poll)} />
        <//>
        <${Row} label=${t('effect')}>
          <select id="effect" class="ctl" value=${s.effect || 'none'} aria-label=${t('effect')}
            onChange=${(e) => act(setSetting, 'effect', e.target.value, poll)}>
            ${EFFECTS.map(([id, key]) =>
              html`<option value=${id} selected=${(s.effect || 'none') === id}>${t(key)}</option>`)}
          </select>
        <//>
        <${Row} label=${t('quality')}>
          <select id="quality" class="ctl" value=${qualityKey(s.resolution)} aria-label=${t('quality')}
            onChange=${(e) => act(setSetting, 'quality', e.target.value, poll)}>
            ${QUALITIES.map(([id, label]) =>
              html`<option value=${id} selected=${qualityKey(s.resolution) === id}>${label}</option>`)}
          </select>
        <//>
        <${JpegQuality} value=${s.jpeg_quality != null ? s.jpeg_quality : 82}
          onInput=${(v) => act(setSetting, 'jpeg_quality', v, poll)} />
        <${Orientation} value=${s.orientation || 'landscape'}
          options=${(s.avail && s.avail.orientation) || ORIENTATIONS}
          onChange=${(v) => act(setSetting, 'orientation', v, poll)} />
        <${Row} label=${t('viewers')}>
          <span class="val">${s.viewers || 0}</span>
        <//>
      </section>

      <section>
        <h2>${t('image')}</h2>
        <dl>
          <dt>${t('bitrate')}</dt><dd>${s.video_bitrate_kbps ? s.video_bitrate_kbps + ' kbps' : '–'}</dd>
          <dt>${t('frames')}</dt><dd>${s.frames != null ? s.frames : '–'}</dd>
          <dt>${t('effect')}</dt><dd>${s.effect || '–'}</dd>
        </dl>
      </section>

      <section>
        <h2>${t('security')}</h2>
        <${Row} label=${t('nightVision')}>
          <!-- onChange receives the NEXT state, not the current one. These
               three call sites used to discard it and send the literal string
               "toggle", which the phone reads as a fixed value: for
               night_vision ("value != off") that is always ON, for
               motion_detection ("value == on") always OFF. So night vision
               could not be turned off and motion could not be turned back on
               -- the button looked fine and did the opposite of what its label
               said. Sending the real state works for every setting and needs no
               server-side toggle. -->
          <${Toggle} id="bNight" on=${!!s.night_vision} label=${t('nightVision')}
            onChange=${(v) => act(setSetting, 'night_vision', v ? 'on' : 'off', poll)} />
        <//>
        <${Row} label=${t('motion')}>
          <${Toggle} id="bMotion" on=${!!motion.enabled} label=${t('motion')}
            onChange=${(v) => act(setSetting, 'motion_detection', v ? 'on' : 'off', poll)} />
        <//>
        <${Row} label=${t('sensitivity')}>
          <${Slider} value=${motion.sensitivity != null ? motion.sensitivity : 5} min=${1} max=${10} step=${1}
            id="sens"
            label=${t('sensitivity')}
            onInput=${(v) => act(setSetting, 'motion_sensitivity', v, poll)} />
          <span class="val">${motion.sensitivity != null ? motion.sensitivity : '–'}</span>
        <//>
        <${Row} label=${t('preRecord')}>
          <${Slider} value=${preRecord} min=${0} max=${10} step=${1}
            id="pre"
            label=${t('preRecord')}
            onInput=${(v) => { setPreRecord(v); act(setSetting, 'pre_record_seconds', v, poll); }} />
          <span class="val">${preRecord}${t('sec')}</span>
        <//>
        <${Row} label=${t('maxClip')}>
          <${Slider} value=${maxClip} min=${10} max=${600} step=${10}
            id="clip"
            label=${t('maxClip')}
            onInput=${(v) => { setMaxClip(v); act(setSetting, 'max_clip_seconds', v, poll); }} />
          <span class="val">${maxClip}${t('sec')}</span>
        <//>
      </section>

      <section>
        <h2>${t('audio')}</h2>
        <${Row} label=${t('audioEnabled')}>
          <${Toggle} id="bAudOn" on=${!!audio.enabled} label=${t('audioEnabled')}
            onChange=${(v) => act(setSetting, 'audio_enabled', v ? 'on' : 'off', poll)} />
          <span class="dim">${audio.enabled ? t('audioOn') : t('audioOff')}</span>
        <//>
        ${AudioCodecs(audio.available).map(([id, key]) => html`
          <${Row} label=${t(key)}>
            <input id=${'ac_' + id} type="radio" name="acodec" value=${id}
              aria-label=${t(key)} checked=${(audio.codec || 'none') === id}
              onChange=${() => act(setAudioCodec, id, poll)} />
            <span class="dim">${t('audioNote_' + id)}</span>
          <//>`)}
        <p class="dim">${t('audioNote')}</p>
        <dl>
          <dt>${t('audioCodec')}</dt><dd>${audio.enabled ? audio.codec : t('off')}</dd>
          <dt>${t('available')}</dt><dd>${audio.available || '–'}</dd>
        </dl>
      </section>

      <section class="files">
        <h2>${t('recordings')} ${recordings.length}</h2>
        <${Row} label=${t('recording')}>
          <${Toggle} id="bRec" on=${!!recording.enabled} label=${t('recording')}
            onChange=${(v) => act(setSetting, 'recording', v ? 'on' : 'off',
              async () => { await poll(); await loadLists(); })} />
        <//>
        <div class="row">
          <button id="bRefreshRec" class="ctl" onClick=${() => act(loadLists)}>${t('refresh')}</button>
          <!-- Disabled on an empty list rather than a no-op click: the bulk
               delete walks the list client-side, so with nothing recorded it
               sends no request at all, and a live-looking button that does
               nothing is the same failure as the undefined helpers were. -->
          <button id="bDelAll" class="ctl danger" disabled=${recordings.length === 0}
            onClick=${() => act(deleteAllRecordings, recordings, loadLists)}
            >${t('deleteAll')}</button>
        </div>
        ${recordings.length === 0
          ? html`<p class="dim">${t('noRecordings')}</p>`
          : recordings.map((r) => html`
              <div class="row file">
                <span class="name" title=${r.name}>${r.name}</span>
                <span class="size">${bytes(r.size)}</span>
                <a class="ctl" href=${'/recordings/' + encodeURIComponent(r.name)}
                  download title=${t('download')}>↓</a>
                <button class="ctl" onClick=${() => act(deleteRecording, r.name, loadLists)}
                  title=${t('deleteClip')}>✕</button>
              </div>`)}
      </section>

      <section class="files">
        <h2>${t('clips')} ${clips.length}</h2>
        <!-- Telemetry the old UI showed and this one had dropped entirely.
             "Record 30s" used to give no feedback at all while it wrote, and a
             failing write reported nothing -- the error field is the phone's
             own clipState error, and dropped frames are the one number that
             says the encoder was falling behind. -->
        ${clipRec && html`
          <dl class="kv">
            <dt>${t('recState')}</dt>
            <dd>${clipRec.active ? t('recording') : clipRec.armed ? t('armed') : t('idle')}</dd>
            ${clipRec.file && html`<dt>${t('recFile')}</dt><dd>${clipRec.file}</dd>`}
            <dt>${t('recFrames')}</dt><dd>${clipRec.frames}</dd>
            ${clipRec.dropped > 0 && html`<dt>${t('recDropped')}</dt>
              <dd class="warn">${clipRec.dropped}</dd>`}
            ${clipRec.bytes > 0 && html`<dt>${t('recBytes')}</dt>
              <dd>${bytes(clipRec.bytes)}</dd>`}
            ${clipRec.error && html`<dt>${t('recError')}</dt>
              <dd class="warn">${clipRec.error}</dd>`}
          </dl>`}
        <div class="row">
          <button id="bRecNow" class="ctl primary" onClick=${() => act(recordNow, 30, loadLists)}
            >${t('recordNow')}</button>
          <button id="bRecStop" class="ctl" onClick=${() => act(stopClipRecording, loadLists)}
            >${t('stopClip')}</button>
          <button id="bRefreshClips" class="ctl" onClick=${() => act(loadLists)}>${t('refresh')}</button>
          <button id="bPruneClips" class="ctl" disabled=${clips.length === 0}
            onClick=${() => act(pruneClips, loadLists)}
            >${t('applyLimits')}</button>
          <button id="bClearClips" class="ctl danger" disabled=${clips.length === 0}
            onClick=${() => act(clearClips, loadLists)}
            >${t('deleteAll')}</button>
        </div>
        ${clips.length === 0
          ? html`<p class="dim">${t('noClips')}</p>`
          : clips.map((c) => html`
              <div class="row file">
                <span class="name" title=${c.name}
                  onClick=${() => setClipPreview(c.name)}>${c.name}</span>
                <span class="size">${bytes(c.size)}</span>
                <a class="ctl" href=${'/clips/' + encodeURIComponent(c.name)}
                  download title=${t('download')}>↓</a>
                <button id="clipDelete" class="ctl"
                  onClick=${() => act(clipOnFile, 'delete', c.name, loadLists)}
                  title=${t('deleteClip')}>✕</button>
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

      ${clipPreview && html`
        <section class="preview">
          <h2>${t('clipPreview')}</h2>
          <video src=${'/clips/' + encodeURIComponent(clipPreview)} controls autoplay
            playsinline style="width:100%;max-height:60vh;background:#000"></video>
          <div class="row">
            <a class="ctl" href=${'/clips/' + encodeURIComponent(clipPreview)} download
              >${t('download')}</a>
            <button id="clipCloseBtn" class="ctl danger"
              onClick=${() => setClipPreview(null)}>${t('close')}</button>
          </div>
        </section>`}

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

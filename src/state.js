// OcuBea WebUI — talking to the phone.
//
// Split from app.jsx so the components stay about rendering and this file
// stays about the wire. Every request the UI can make originates here.

export { t, LANG } from './i18n.js';

// ── access token ────────────────────────────────────────────────────────────
// A token in the query string is kept, so a bookmarked ?token=… link keeps
// working after a reload. It is mirrored into localStorage so it survives
// navigation to a bare /, where the query is gone.
let TOKEN =
  new URLSearchParams(location.search).get('token') || localStorage.getItem('ocubea_token') || '';

export const getToken = () => TOKEN;

export const setToken = (v) => {
  TOKEN = (v || '').trim();
  if (TOKEN) localStorage.setItem('ocubea_token', TOKEN);
  else localStorage.removeItem('ocubea_token');
};

const withToken = (p) =>
  (TOKEN ? p + (p.indexOf('?') >= 0 ? '&' : '?') + 'token=' + encodeURIComponent(TOKEN) : p);

// ── requests ────────────────────────────────────────────────────────────────
// The phone answers with plain text as often as JSON ("ok", "torch on"), so a
// body is parsed leniently: a non-JSON payload comes back as { text } rather
// than throwing on a response that was in fact a success.
export const api = async (p, opts = {}) => {
  const init = { method: opts.method || 'POST' };
  if (opts.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(opts.body);
  }
  const res = await fetch(withToken(p), init);
  if (!res.ok) throw new Error(p + ' → ' + res.status);
  const text = await res.text();
  if (!text) return {};
  try { return JSON.parse(text); } catch { return { text }; }
};

export const get = (p) => api(p, { method: 'GET' });

// The phone's endpoints disagree about where a parameter lives, and getting
// this wrong is silent: the request returns 200 "ok" and nothing changes.
//
//   /settings/<name>?set=<value>   query string
//   /ptz?zoom=<n>                  query string
//   /recordings/delete?name=<f>    query string
//
// So a JSON body is not merely ignored — it is accepted as "ok". These two
// helpers make the shape explicit at the call site.
export const setSetting = (name, value) =>
  api('/settings/' + name + '?set=' + encodeURIComponent(String(value)), { method: 'POST' });

export const ptz = (params) => {
  const q = Object.keys(params)
    .map((k) => k + '=' + encodeURIComponent(String(params[k])))
    .join('&');
  return api('/ptz?' + q, { method: 'POST' });
};

export const deleteRecording = (name) =>
  api('/recordings/delete?name=' + encodeURIComponent(name), { method: 'POST' });

// ── clips ───────────────────────────────────────────────────────────────────
// The clip actions come in two shapes, measured on the phone:
//
//   /clips/record?seconds=<n>   POST, no name needed → {"recording":true,...}
//   /clips/record/stop          POST, no name needed → {"recording":false}
//   /clips/<action>?name=<file> POST, name required, else 400 "invalid clip name"
//
// prune and clear are the two that name a file. The 400 is the tell: a
// request without the parameter fails, one with it returns 200.
export const clipOnFile = (action, name) =>
  api('/clips/' + action + '?name=' + encodeURIComponent(name), { method: 'POST' });

export const recordNow = (seconds) =>
  api('/clips/record?seconds=' + encodeURIComponent(String(seconds)), { method: 'POST' });

export const stopClipRecording = () => api('/clips/record/stop', { method: 'POST' });

// ── bulk actions ────────────────────────────────────────────────────────────
// prune and clear act on the WHOLE collection and read no parameter. The
// contract was verified against the server, not the old UI: clipsClear() and
// clipsPrune() iterate ClipStorage.list() and never look at the query string,
// so passing ?name= is not merely redundant -- clipOnFile('clear', name) looks
// like it deletes one file and in fact deletes everything but the open clip.
//
// That is also why clipOnFile is right for a single clip and wrong for these:
// the server answers a name-less POST /clips/<action> with
// 400 "invalid clip name", which is the tell that a name was required.
/** Delete every clip except the one currently being written. */
export const clearClips = () => api('/clips/clear', { method: 'POST' });

/** Apply the retention limits (bytes, age, file count) to the whole collection. */
export const pruneClips = () => api('/clips/prune', { method: 'POST' });

// /recordings has no bulk delete: handleRecordings only routes POST delete,
// which takes one name. So "delete all" has to be the list walked client-side.
// It reports how many actually went, because the single-file endpoint answers
// "not found" with a 200 and a careless loop would claim success for all.
export const deleteAllRecordings = async (recordings) => {
  const gone = [];
  for (const r of recordings) {
    const res = await api(
      '/recordings/delete?name=' + encodeURIComponent(r.name), { method: 'POST' });
    if (res.text !== 'not found') gone.push(r.name);
  }
  return { deleted: gone.length, failed: recordings.length - gone.length };
};

// Autofocus takes normalised coordinates, not pixels: 0.5/0.5 is the centre.
export const focus = (x, y) =>
  api('/focus?x=' + x + '&y=' + y, { method: 'POST' });

// The audio codec takes its value in the query string like the settings do. A
// JSON body is answered {"error":"codec is required"} -- measured, not assumed.
export const setAudioCodec = (id) =>
  api('/audio/codec?codec=' + encodeURIComponent(id), { method: 'POST' });

// HLS tuning knobs, read as "profile=default segment_ms=250 keyframe_sec=0
// sync=3 buffer=6" rather than JSON.
export const hlsProfile = () => get('/hls/profile');

// POST /hls/profile?set=low|high|default. Switching restarts the encoder, so
// the media sequence restarts and an open viewer has to reload -- unavoidable,
// KEY_I_FRAME_INTERVAL is a codec-config value.
export const setHlsProfile = (name) =>
  api('/hls/profile?set=' + encodeURIComponent(name), { method: 'POST' });

// ── formatting ──────────────────────────────────────────────────────────────
export const bytes = (n) => {
  if (!n) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), 3);
  return (n / Math.pow(1024, i)).toFixed(i ? 1 : 0) + ' ' + u[i];
};

export const uptime = (s) => {
  s = Math.max(0, Math.floor(s));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? h + 'h ' + m + 'm' : m + 'm';
};

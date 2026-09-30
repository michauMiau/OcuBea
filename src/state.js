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

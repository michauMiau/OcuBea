// OcuBea WebUI — bilingual strings.
//
// Polish is the default; English is the fallback. Kept as a plain table so a
// missing key falls through to English rather than rendering a raw key.

export const STR = {
  pl: {
    live: 'na żywo', stopped: 'zatrzymany', fps: 'kl./s',
    start: 'Start strumienia', stop: 'Zatrzymaj strumień',
    mode: 'Tryb', modeMjpeg: 'MJPEG', modeHls: 'HLS',
    lowLatency: 'Niska latencja', on: 'wł.', off: 'wył.',
    optics: 'Optyka', image: 'Obraz', security: 'Kamera bezpieczeństwa',
    status: 'Status', audio: 'Strumień audio', recordings: 'Nagrania',
    clips: 'Klipsy', device: 'Urządzenie', api: 'API',
    encoder: 'Koder', segments: 'Segmenty HLS', dropped: 'Odrzucone',
    resolution: 'Rozdzielczość', uptime: 'Czas działania', viewers: 'Widzowie',
    zoom: 'Zoom', focus: 'Ostrość', autofocus: 'Autofokus',
    torch: 'Latarka', flip: 'Odwróć kamerę', snapshot: 'Zrzut',
    recording: 'Nagrywanie', noRecordings: 'Brak nagrań', noClips: 'Brak klipsów',
    battery: 'Bateria', storage: 'Wolne miejsce',
    token: 'Token dostępu', tokenHint: 'Zapisuje się lokalnie. Dołącza się do każdego żądania jako ?token=',
    noAudio: 'Bez audio', listen: 'Słuchaj', idle: 'spoczynek',
    streamOffline: 'strumień offline', phoneOffline: 'telefon offline',
    hlsError: 'Błąd HLS', hlsWarming: 'Koder się rozgrzewa…',
    hlsManifest: 'Nie mogę wczytać playlisty HLS',
    hlsFragment: 'Nie mogę pobrać segmentu HLS',
    hlsCodec: 'Ten silnik nie obsługuje kodeka H.264 w MSE',
    hlsReset: 'Odtwarzać HLS od nowa',
    hlsBuffer: 'Bufor odtwarzania HLS się zatrzymał',
    hlsInternal: 'Wewnętrzny błąd hls.js',
  },
  en: {
    live: 'live', stopped: 'stopped', fps: 'fps',
    start: 'Start stream', stop: 'Stop stream',
    mode: 'Mode', modeMjpeg: 'MJPEG', modeHls: 'HLS',
    lowLatency: 'Low latency', on: 'on', off: 'off',
    optics: 'Optics', image: 'Image', security: 'Security camera',
    status: 'Status', audio: 'Audio stream', recordings: 'Recordings',
    clips: 'Clips', device: 'Device', api: 'API',
    encoder: 'Encoder', segments: 'HLS segments', dropped: 'Dropped',
    resolution: 'Resolution', uptime: 'Uptime', viewers: 'Viewers',
    zoom: 'Zoom', focus: 'Focus', autofocus: 'Autofocus',
    torch: 'Torch', flip: 'Flip cam', snapshot: 'Snapshot',
    recording: 'Recording', noRecordings: 'No recordings', noClips: 'No clips',
    battery: 'Battery', storage: 'Free storage',
    token: 'Access token', tokenHint: 'Stored locally. Appended to every request as ?token=',
    noAudio: 'No audio', listen: 'Listen', idle: 'idle',
    streamOffline: 'stream offline', phoneOffline: 'phone offline',
    hlsError: 'HLS error', hlsWarming: 'Encoder warming up…',
    hlsManifest: 'Could not load the HLS playlist',
    hlsFragment: 'Could not fetch an HLS segment',
    hlsCodec: 'This engine cannot decode H.264 in MSE',
    hlsReset: 'Rebuilding the HLS MediaSource',
    hlsBuffer: 'The HLS playback buffer stalled',
    hlsInternal: 'hls.js internal error',
  },
};

// ?lang= wins, then the stored choice, then the browser's own preference.
//
// The parentheses around the third operand are load-bearing: a bare
// `q || saved || own.startsWith('pl') ? 'pl' : 'en'` parses as
// `(q || saved || own.startsWith('pl')) ? 'pl' : 'en'`, so ?lang=en would
// still select Polish.
const pick = () => {
  const q = new URLSearchParams(location.search).get('lang');
  const saved = localStorage.getItem('ocubea_lang');
  const guess = (navigator.language || '').toLowerCase().startsWith('pl') ? 'pl' : 'en';
  return STR[q] || STR[saved] || STR[guess] || STR.en;
};

// The resolved language code, exported so the UI's switch reflects the text
// that is actually on screen instead of keeping a second opinion. pick() is
// called once: a second call would re-read localStorage for no reason.
const chosen = pick();

export const LANG = STR[chosen] ? chosen : 'en';
export const T = STR[LANG];
export const t = (key) => T[key] ?? STR.en[key] ?? key;

// Measure the playlist window only AFTER the ring has filled, so a number taken
// while it is still filling cannot be mistaken for the steady state. The first
// reading after a restart is always short: 0.91s -> 0.74s looked like an
// improvement between two profiles when it was just the ring growing.
const H = 'http://192.168.1.184:8080';

async function playlistSeconds() {
  const t = await (await fetch(H + '/hls.m3u8?' + Date.now())).text();
  let end = 0;
  for (const line of t.split('\n')) {
    if (line.startsWith('#EXTINF:')) end += parseFloat(line.split(':')[1]);
  }
  const n = (t.match(/seg\d+\.m4s/g) || []).length;
  return { end, n };
}

async function profile() {
  const t = await (await fetch(H + '/hls/profile')).text();
  return (t.match(/profile=(\w+)/) || [])[1];
}

const stable = async (label) => {
  const seen = [];
  for (let i = 0; i < 12; i++) {
    seen.push((await playlistSeconds()).end);
    await new Promise(r => setTimeout(r, 1500));
  }
  // Take the last 6: the ring is full by then and the tail is the real window.
  const tail = seen.slice(-6);
  const med = tail.slice().sort((a, b) => a - b)[Math.floor(tail.length / 2)];
  console.log(`${label}: playlista ${tail.map(x => x.toFixed(2)).join(', ')} -> mediana ${med.toFixed(2)} s`);
  return med;
};

(async () => {
  await fetch(H + '/hls/profile?set=low', { method: 'POST' });
  await new Promise(r => setTimeout(r, 4000));
  console.log('profil:', await profile());
  const low = await stable('LOW_LATENCY');

  await fetch(H + '/hls/profile?set=default', { method: 'POST' });
  await new Promise(r => setTimeout(r, 4000));
  console.log('profil:', await profile());
  const def = await stable('DEFAULT     ');

  console.log(`\n>>> okno maleje o ${(def - low).toFixed(2)} s (${(def / low).toFixed(1)}x mniej)`);
})();

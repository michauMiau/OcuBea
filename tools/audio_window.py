#!/usr/bin/env python3
"""Every audio counter and the decoded result, from one window.

The earlier diagnosis compared counters read before one download against audio
decoded from a different download. That produced sentences like "the codec
returned 30% of the audio it was handed", where the 30% and the "handed" came
from different ten-second windows and could not both be true. Nothing here
crosses a window boundary: select codec, settle, snapshot counters, download,
snapshot counters again, decode the file just downloaded.

Prints the one ratio that matters -- decoded audio against audio fed -- so a
loss can be attributed to the encoder or to the path, not guessed at.

    python3 tools/audio_window.py flac --secs 20
"""
import argparse
import json
import os
import subprocess
import sys
import time
from urllib.request import Request, urlopen

HOST = 'http://192.168.1.184:8080'
RATE = 44100
FRAME_SAMPLES = 960


def counters():
    with urlopen(HOST + '/status.json', timeout=20) as r:
        return json.loads(r.read()).get('audio', {}).get('encoded', {})


def set_codec(codec):
    req = Request(HOST + '/audio/codec', data=f'codec={codec}'.encode(),
                  method='POST')
    with urlopen(req, timeout=15) as r:
        return r.read().decode(errors='replace').strip()


def measure(codec, secs):
    print(f'  set codec: {set_codec(codec)}')
    time.sleep(2.5)
    before = counters()
    path = f'/tmp/win_{codec}.{codec}'
    t0 = time.time()
    subprocess.run(['curl', '-s', '-m', str(secs + 2), '-o', path,
                    f'{HOST}/audio.{codec}'], capture_output=True)
    wall = time.time() - t0
    after = counters()
    d = {k: after.get(k, 0) - before.get(k, 0)
        for k in ('feed_calls', 'packets_out', 'empty_out', 'input_full',
                  'feed_misses', 'carry_bytes', 'carry_drop')}

    decoded = subprocess.run(
        ['ffmpeg', '-v', 'error', '-i', path, '-f', 's16le', '-ac', '1',
         '-ar', str(RATE), '-'], capture_output=True)
    samples = len(decoded.stdout) // 2 if decoded.returncode == 0 else 0

    # Frame count straight from the decoder, never from a parsed header byte.
    # tools/flac_block_size.py read the block-size nibble as 576 when ffprobe's
    # per-frame nb_samples said 4096, and an 18% loss was built on top of it.
    frames = []
    if codec == 'flac':
        out = subprocess.run(
            ['ffprobe', '-v', 'error', '-show_entries', 'frame=nb_samples',
             '-of', 'csv=p=0', path], capture_output=True, text=True).stdout
        frames = [int(x) for x in out.split() if x.strip().isdigit()]

    return d, samples, frames, os.path.getsize(path), wall, decoded.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('codec', nargs='?', default='flac')
    ap.add_argument('--secs', type=int, default=20)
    ap.add_argument('--host', default=HOST)
    args = ap.parse_args()

    d, samples, frames, size, wall, rc = measure(args.codec, args.secs)

    fed = d['feed_calls'] * FRAME_SAMPLES
    print(f'  plik {size} B, {wall:.1f}s sciagniecia, ffmpeg rc={rc}')
    print()
    print(f'  feed_calls    {d["feed_calls"]:6d}  -> {fed:9d} probek = {fed/RATE:6.2f}s audio na wejscie')
    print(f'  input_full    {d["input_full"]:6d}  blokow NIE zakolejkowanych')
    print(f'  packets_out   {d["packets_out"]:6d}  ramek wyprodukowanych')
    print(f'  empty_out     {d["empty_out"]:6d}  pustych drainow')
    print(f'  feed_misses   {d["feed_misses"]:6d}')
    print(f'  carry_bytes   {d["carry_bytes"]:6d}  (po oknie, nie delta)')
    print(f'  carry_drop    {d["carry_drop"]:6d}  bajtów strąconych przez carry overflow')
    print()
    print(f'  ZDEKODOWANE   {samples:9d} probek = {samples/RATE:6.2f}s audio na wyjsciu')
    if fed:
        print(f'  stosunek      {samples/fed*100:6.1f}%  audio na wyjscie / na wejscie')
    if frames:
        uniq = sorted(set(frames))
        print(f'  ramki         {len(frames):6d} x {uniq} probek '
              f'= {sum(frames)} probek = {sum(frames)/RATE:.2f}s')
        exp = fed // max(uniq) if uniq else 0
        if exp:
            print(f'  oczekiwane    {exp:6d} ramek przy {uniq[0]} probek/blok')
            print(f'  BRAKUJE       {exp - len(frames):6d} ramek '
                  f'= {(exp-len(frames))*uniq[0]} probek')
    print(f'  realny czas   {samples/RATE:.2f}s audio w {wall:.1f}s sciagniecia')
    return 0


if __name__ == '__main__':
    sys.exit(main())
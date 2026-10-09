#!/usr/bin/env python3
"""Gate: every audio codec must carry the same audio rate as WAV.

Why this gate exists. AAC and FLAC both stutter while WAV is clean, and the
decode-only check said both were perfect: ffmpeg exited 0, zero stderr, every
AAC frame walked cleanly. Decoding proves the bytes are well formed. It says
nothing about how much audio those bytes carry, which is where the stutter was
hiding.

That gap let two wrong fixes through. Both read AAC's 1024.0 samples-per-frame
as corruption -- 6.7% too much audio -- and both were wrong, because 1024 is the
encoder's native AAC-LC frame size and the audio RATE is exact: 457728 samples
in 10.38 s is 44105 samples/s against a 44100 Hz source. Meanwhile FLAC was
quietly losing 38% of the audio in the same capture window.

So the check is samples against elapsed time, per codec, with WAV as the
reference, measured from the served bytes rather than from anything the app
reports about itself.

- `--host` the phone running OcuBea. Needs the audio endpoint reachable.
- `--tolerance` how far a codec's sample rate may sit from WAV's, as a
  fraction. 0.10 by default: measured AAC is within 0.01%, and FLAC's real
  failure is 38%, so the default separates the two by a wide margin.
- `--self-test` mutate the arithmetic to prove the comparison can fail.

Exit 0 when every codec is in range, 1 when one is out, 2 when the phone could
not be reached at all -- which is not the same as "the codecs are fine".
"""
import argparse
import statistics
import struct
import subprocess
import sys

REFERENCE_RATE = 44100
SAMPLE_RATE = 44100


def fetch(host, path, out, secs):
    """Pull a fixed wall-clock window of a live stream.

    The endpoint never ends, so curl always exits 28 (timeout) having written a
    valid partial file. Checking the return code called that a failure while
    the server was streaming happily, which is how the first version of this
    gate reported "unreachable" for a phone answering 200. Judge by bytes.
    """
    subprocess.run(['curl', '-s', '-m', str(secs + 2), '-o', out,
                    f'{host}{path}'], capture_output=True)
    try:
        return int(subprocess.run(['stat', '-c', '%s', out],
                                  capture_output=True, text=True).stdout)
    except ValueError:
        return 0


def decode_samples(path):
    """Decode to mono s16le and return the sample count."""
    r = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 's16le',
                        '-ac', '1', '-ar', str(SAMPLE_RATE), '-'],
                       capture_output=True)
    if r.returncode != 0:
        return None, r.stderr.decode()[:300]
    return len(r.stdout) // 2, ''


def measure(host, secs, codecs):
    out = {}
    for name, path in codecs:
        local = f'/tmp/gate_{name}'
        size = fetch(host, path, local, secs)
        if size < 4096:
            out[name] = (None, f'only {size} B served')
            continue
        samples, err = decode_samples(local)
        if samples is None:
            out[name] = (None, f'decode failed: {err}')
            continue
        out[name] = (samples / SAMPLE_RATE, '')
    return out


CODECS = (('wav', '/audio.wav'), ('aac', '/audio.aac'), ('flac', '/audio.flac'))


def evaluate(rates, tolerance, self_test=False):
    """Compare each codec's rate against WAV's. Returns (ok, lines).

    `rates` maps a codec id to (seconds, error). Keeping the error beside the
    value avoids guessing on the failure path: an earlier version mixed tuples
    and floats and crashed on the very codec it was supposed to report.
    """
    lines = []
    ref_pair = rates.get('wav')
    if ref_pair is None or ref_pair[0] is None:
        return False, ['  FAIL wav reference unavailable, cannot judge the rest']
    ref = ref_pair[0]
    ok = True
    for name, pair in rates.items():
        rate, err = pair if isinstance(pair, tuple) else (pair, '')
        if rate is None:
            lines.append(f'  FAIL {name}: {err or "unavailable"}')
            ok = False
            continue
        if name == 'wav':
            lines.append(f'  ref  {name:5} {rate:8.2f} s of audio')
            continue
        if self_test:
            # Deliberately wrong so the comparison is proven able to go red.
            rate = ref * 1.38
        drift = abs(rate - ref) / ref
        good = drift <= tolerance
        ok = ok and good
        lines.append(
            f'  {"OK  " if good else "FAIL"} {name:5} {rate:8.2f} s of audio, '
            f'{drift * 100:5.1f}% from wav (limit {tolerance * 100:.0f}%)')
    return ok, lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='http://192.168.1.184:8080')
    ap.add_argument('--secs', type=int, default=8)
    ap.add_argument('--tolerance', type=float, default=0.10)
    ap.add_argument('--self-test', action='store_true')
    args = ap.parse_args()

    if args.self_test:
        # Tuples, matching what measure() returns, so the self-test exercises
        # the same shape the live path does. Plain floats crashed on ref_pair[0].
        rates = {'wav': (10.08, ''), 'aac': (9.75, ''), 'flac': (6.22, '')}
        ok, lines = evaluate(rates, args.tolerance, self_test=True)
        print('\n'.join(lines))
        print()
        print('  self-test: the mutation must make this FAIL')
        print('  OK' if not ok else '  BROKEN: the gate passed a 38% drift')
        return 0 if not ok else 1

    rates = measure(args.host, args.secs, CODECS)
    print(f'  {args.host}, {args.secs}s per codec')
    ok, lines = evaluate(rates, args.tolerance)
    print('\n'.join(lines))
    print()
    if rates.get('wav', (None, ''))[0] is None:
        print('  phone unreachable, that is NOT a pass')
        return 2
    if not ok:
        print('  an encoder is not carrying the audio the microphone captured')
    print('  OK' if ok else '  FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
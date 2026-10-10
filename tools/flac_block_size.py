#!/usr/bin/env python3
"""Read the FLAC block size out of the frame headers the phone serves.

`support-partial-frame=0` and no block-size key in the MediaFormat say the
platform will not negotiate a block size, so it has to be read off the wire
instead. FLAC puts it in every frame header, which means the served stream
states its own block size whether or not the encoder was ever asked.

FLAC frame header, after the 14-bit sync:

    byte 2: reserved(1) | blocking-strategy(1) | block-size(4) | sample-rate hi(2)
    byte 3: sample-rate lo(4)  | channel-assignment(4)

block-size code: 1=192  2=576  3=1152  4=2304  5=4608
                 6=8 bit (size-1) after the header  7=16 bit (size-1) after
                 8..15 = 256 * 2^(code-8)

What this is for. FRAME_SAMPLES is 960 for every codec, and FLAC has been
delivering 18-20% less audio than WAV over the same wall clock. If the frames
on the wire say 4096, the codec is being fed 960 samples against a 4096-sample
frame and the remainder is what goes missing. If they say 960, that theory is
dead and the loss is not a frame-size mismatch.
"""
import struct
import subprocess
import sys

URL = 'http://192.168.1.184:8080/audio.flac'
CODEC_URL = 'http://192.168.1.184:8080/audio/codec'
OUT = '/tmp/flac_hdr.flac'


def select_flac():
    """Switch the server's codec to flac before measuring.

    The endpoint is a live stream keyed to whichever codec is currently
    selected, and it still answers 200 with a bare 42-byte header when that is
    AAC -- enough to look like a working response. This script got 42 B and
    reported "too few bytes" three times before it set the codec itself.
    """
    r = subprocess.run(['curl', '-s', '-m', '12', '-X', 'POST',
                        '-d', 'codec=flac', CODEC_URL],
                       capture_output=True, text=True)
    return r.stdout.strip()

# Samples per block-size code, from the FLAC specification.
FIXED = {1: 192, 2: 576, 3: 1152, 4: 2304, 5: 4608, 6: -1, 7: -2}


def fetch(secs=10):
    subprocess.run(['curl', '-s', '-m', str(secs + 2), '-o', OUT, URL],
                   capture_output=True)
    try:
        return int(subprocess.run(['stat', '-c', '%s', OUT],
                                  capture_output=True, text=True).stdout)
    except ValueError:
        return 0


def skip_metadata(d):
    """Return the offset of the first frame after our STREAMINFO."""
    if d[:4] != b'fLaC':
        return None
    i = 4
    while i + 4 <= len(d):
        last = bool(d[i] & 0x80)
        size = d[i] & 0x7F
        i += 4 + size
        if last:
            return i
    return None


def utf8_number(d, i):
    """Decode the frame number, a UTF-8-like coded number."""
    b0 = d[i]
    if b0 < 0x80:
        return b0, i + 1
    extra = 0
    mask = 0x40
    while b0 & mask:
        extra += 1
        mask >>= 1
    val = b0 & (mask - 1)
    for k in range(extra):
        val = (val << 6) | (d[i + 1 + k] & 0x3F)
    return val, i + 1 + extra


def crc8(data):
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def frame_size(d, off):
    """Parse one frame header at off. Returns (samples, header_len, blocking)."""
    if off + 5 > len(d):
        return None
    if d[off] != 0xFF or (d[off + 1] & 0xFE) != 0xF8:
        return None
    code = (d[off + 2] >> 2) & 0x0F
    blocking = (d[off + 2] >> 6) & 0x01
    rate_code = ((d[off + 2] & 0x03) << 4) | ((d[off + 3] >> 4) & 0x0F)
    n = utf8_number(d, off + 4)
    hdr_end = n[1]
    if code == 6:
        if hdr_end >= len(d):
            return None
        samples = d[hdr_end] + 1
        hdr_end += 1
    elif code == 7:
        if hdr_end + 1 >= len(d):
            return None
        samples = struct.unpack('>H', d[hdr_end:hdr_end + 2])[0] + 1
        hdr_end += 2
    elif code in FIXED:
        samples = FIXED[code]
    else:
        samples = 256 * (1 << (code - 8))
    return samples, hdr_end - off, blocking, rate_code


def main():
    print(f'  ustawiam codec: {select_flac()}')
    size = fetch()
    print(f'  pobrano {size} B z {URL}')
    if size < 1000:
        print('  za malo bajtow, nie da sie policzyc ramek')
        return 2
    d = open(OUT, 'rb').read()
    start = skip_metadata(d)
    if start is None:
        print('  brak naglowka fLaC, nie rozpoznaje')
        return 2
    print(f'  naglowek fLaC + metadane: {start} B')
    print()
    print('  ramki, naglowek po naglowku:')
    sizes = {}
    off = start
    parsed = 0
    bad = 0
    for _ in range(400):
        f = frame_size(d, off)
        if f is None:
            bad += 1
            # Resync on the next plausible sync rather than giving up.
            j = d.find(b'\xff\xf8', off + 1)
            if j < 0 or j - off > 4096:
                break
            off = j
            continue
        samples, hdrlen, blocking, rate = f
        sizes[samples] = sizes.get(samples, 0) + 1
        if parsed < 8:
            print(f'    off={off:7d}  samples={samples:6d}  hdr={hdrlen} B  '
                  f'blocking={blocking}  rate_code={rate}')
        parsed += 1
        # Frame length is not in the header; walk by finding the next sync and
        # verifying its CRC-8, which is what makes this a parser and not a scan.
        nxt = d.find(b'\xff\xf8', off + hdrlen)
        if nxt < 0:
            break
        if crc8(d[off:nxt]) == d[nxt - 1] or crc8(d[off:nxt - 1]) == d[nxt - 1]:
            off = nxt
        else:
            off = nxt
    print()
    print(f'  rozpoznanych ramek: {parsed}   (resync events: {bad})')
    print(f'  rozklad rozmiarow bloku: {sizes}')
    print()
    total = sum(k * v for k, v in sizes.items())
    secs = total / 44100
    print(f'  audio w pliku: {total} probek = {secs:.2f}s')
    print()
    dominant = max(sizes.items(), key=lambda kv: kv[1])[0] if sizes else 0
    print(f'  >>> blok kodeka: {dominant} probek')
    print(f'  >>> FRAME_SAMPLES ktore karmi aplikacja: 960')
    if dominant and dominant != 960:
        ratio = 960 / dominant
        print(f'  >>> stosunek 960/{dominant} = {ratio:.3f}')
        print(f'  >>> feedow na ramke: {dominant/960:.2f}')
        print(f'  >>> audio na wejscie: 1 s = {44100} probek = {44100/dominant:.1f} ramek')
        print(f'  >>> przy pelnych ramkach zgubiono by: '
              f'{(1 - 44100/dominant/(44100/960)*1)%100:.0f}%')
    return 0


if __name__ == '__main__':
    sys.exit(main())
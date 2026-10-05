#!/usr/bin/env python3
"""Find the lightest pixel inside each radio button and slider thumb.

vision_analyze reported a "white circle" on the selected audio codec radio and on
the slider thumbs, while shoot_web_ui.py reported light_backgrounds: []. The CSS
says appearance:none with background #0d1117 and a var(--acc) ::before, so one of
them is wrong and reading about it cannot settle it.

Settle it on pixels: sample the element's own rectangle and report the brightest
pixel found, with its coordinates. A white ball is a run of near-white pixels
inside the control. An accent-green 6px dot on a dark ring is not.
"""
from __future__ import annotations

import struct
import sys
import zlib

sys.path.insert(0, "/root/OcuBea/tools")
import serve_and_measure  # noqa: E402

URL = sys.argv[1] if len(sys.argv) > 1 else None


def png_pixels(path: str) -> tuple[int, int, bytes, int]:
    """Minimal PNG reader for RGBA/RGB 8-bit, no interlacing.

    Returns (width, height, packed rows, channels).
    """
    raw = open(path, "rb").read()
    assert raw[:8] == b"\x89PNG\r\n\x1a\n", "nie PNG"
    pos, idat, w, h, bd, ct = 8, b"", 0, 0, 0, 0
    while pos < len(raw):
        ln = struct.unpack(">I", raw[pos:pos + 4])[0]
        typ = raw[pos + 4:pos + 8]
        data = raw[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", data[:10])
        elif typ == b"IDAT":
            idat += data
        elif typ == b"IEND":
            break
        pos += 12 + ln
    assert bd == 8 and ct in (2, 6), f"nieobslugiwany PNG bd={bd} ct={ct}"
    ch = 3 if ct == 2 else 4
    dec = zlib.decompress(idat)
    stride = w * ch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = dec[p]
        p += 1
        line = bytearray(dec[p:p + stride])
        p += stride
        if f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, bytes(out), ch


def brightest(path: str, rect: dict, label: str) -> None:
    """Report the brightest pixel inside rect (CSS coords -> image pixels)."""
    w, h, px, ch = png_pixels(path)
    sx = w / 412.0  # shots are captured at device_scale_factor 2
    x0 = max(0, int(rect["x"] * sx))
    x1 = min(w, int((rect["x"] + rect["width"]) * sx))
    y0 = max(0, int(rect["y"] * sx))
    y1 = min(h, int((rect["y"] + rect["height"]) * sx))
    if x1 <= x0 or y1 <= y0:
        print(f"  {label:22s} brak pikseli w {rect}")
        return
    best = (0, 0, 0, (0, 0, 0))
    white_run = 0
    for y in range(y0, y1):
        base = y * w * ch
        for x in range(x0, x1):
            o = base + x * ch
            r, g, b = px[o], px[o + 1], px[o + 2]
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if lum > best[0]:
                best = (lum, x, y, (r, g, b))
            if r > 200 and g > 200 and b > 200:
                white_run += 1
    lum, x, y, rgb = best
    print(f"  {label:22s} najjasniejszy piksel: rgb{rgb} L={lum:5.1f} "
          f"@({x},{y}) | bialych pikseli w kontenerze: {white_run}")


def main() -> int:
    from playwright.sync_api import sync_playwright

    shot = "/root/.hermes/cache/scratch/e2e/pixels.png"
    with serve_and_measure.server() as url:
        with sync_playwright() as p:
            b = p.chromium.launch(args=["--no-sandbox"])
            pg = b.new_page(viewport={"width": 412, "height": 915},
                            device_scale_factor=2)
            pg.goto(url, wait_until="load", timeout=60000)
            pg.wait_for_timeout(9000)
            rects = pg.evaluate(
                """() => {
                  const out = {};
                  const add = (label, el) => {
                    if (!el) return;
                    const r = el.getBoundingClientRect();
                    out[label] = {x: r.x + window.scrollX, y: r.y + window.scrollY,
                                  width: r.width, height: r.height};
                  };
                  const radios = [...document.querySelectorAll("input[type=radio]")];
                  add("radio zaznaczony", radios.find(r => r.checked));
                  add("radio niezaznaczony", radios.find(r => !r.checked));
                  const ranges = [...document.querySelectorAll("input[type=range]")];
                  add("slider 1", ranges[0]);
                  add("toggle audio", document.querySelector("#bAudOn"));
                  add("kafelek Torch", document.querySelector("#bTorch"));
                  return out;
                }"""
            )
            pg.screenshot(path=shot, full_page=True)
            b.close()

    print(f"  zrzut: {shot}")
    print()
    for label, r in rects.items():
        brightest(shot, r, label)
    print()
    print("  Interpretacja: biala kula = >=200 na wszystkich kanalach w "
          "duzej liczbie pikseli.")
    print("  Pojedynczy jasny piksel w centrum to akcent, nie kulka.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
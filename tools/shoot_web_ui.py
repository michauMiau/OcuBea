#!/usr/bin/env python3
"""Measure the OcuBea web UI in a real browser and screenshot it.

The phone cannot do this: the A6 has no WebView package installed and the A16
has no browser at all. A gate that only reads index.html therefore cannot answer
the questions the user actually asked -- is the status bar above the stream, is
the white ball gone, are the icons centred, do the dropdowns fit their content.

So render it here. Chromium at a phone viewport, both stream modes, measured
from the live DOM rather than from the CSS text.

Usage:
  python3 tools/shoot_web_ui.py [BASE_URL] [--out DIR]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

from playwright.sync_api import sync_playwright

PHONE = {"width": 412, "height": 915}


def measure(page) -> dict:
    """Read the facts the user complained about, from the laid-out DOM."""
    out: dict = {}

    out["statusbar"] = page.locator(".statusbar").count()
    out["chip"] = page.locator(".chip").count()
    out["tilebtn"] = page.locator(".tilebtn").count()
    out["tilebtn_with_svg"] = page.locator(".tilebtn:has(svg)").count()
    out["tilebtn_with_cap"] = page.locator(".tilebtn .cap").count()

    # Status bar above or below the stream? Compare their vertical positions.
    bar = page.locator(".statusbar").first
    stream = page.locator(".stream").first
    if bar.count() and stream.count():
        bb, bs = bar.bounding_box(), stream.bounding_box()
        if bb and bs:
            out["statusbar_above_stream"] = bb["y"] + bb["height"] <= bs["y"] + 1
            out["statusbar_y"] = round(bb["y"], 1)
            out["stream_y"] = round(bs["y"], 1)

    # Aspect ratio of whatever is currently rendering.
    for sel in (".stream video", ".stream img"):
        loc = page.locator(sel).first
        if not loc.count():
            continue
        if not loc.is_visible():
            out[f"{sel}_visible"] = False
            continue
        bx = loc.bounding_box()
        if not bx or not bx["height"]:
            continue
        out[f"{sel}_visible"] = True
        out[f"{sel}_ratio"] = round(bx["width"] / bx["height"], 3)
        out[f"{sel}_box"] = [round(bx["width"], 1), round(bx["height"], 1)]

    # The white ball: a range input with no custom thumb renders Chrome's
    # default white circle. Ask the browser what the thumb actually is.
    slider = page.locator("input[type=range]").first
    if slider.count():
        thumb = page.evaluate(
            """() => {
              const el = document.querySelector("input[type=range]");
              if (!el) return null;
              const cs = getComputedStyle(el);
              return {
                appearance: cs.appearance,
                webkitAppearance: cs.webkitAppearance,
                height: cs.height,
                width: cs.width,
              };
            }"""
        )
        out["slider"] = thumb
        out["slider_has_custom_thumb"] = bool(thumb) and (
            thumb.get("webkitAppearance") == "none" or thumb.get("appearance") == "none"
        )

    # Icon tiles: are they actually a ROW, and how much of it is wasted?
    #
    # A width check alone cannot see the failure this replaces. Three tiles at
    # exactly 84px each passed every width assertion while sitting in ONE column
    # (all x=21, y=596/648/700) inside a 370px container — 286px, 77% of the row
    # gone, which is precisely the "dużo zmarnowanego miejsca" complaint. The
    # CSS read `repeat(auto-fill, minmax(84px, fit-content(112px)))` and resolved
    # to one track, because a function-valued minmax maximum is not something
    # auto-fill can distribute.
    #
    # So count distinct x and y positions instead of reading widths. A stack of
    # correctly-sized tiles fails this and passes everything else.
    tiles = page.evaluate(
        """() => {
          const out = [];
          for (const grid of document.querySelectorAll(".tiles")) {
            const g = grid.getBoundingClientRect();
            const cs = getComputedStyle(grid);
            const items = [...grid.querySelectorAll(".tilebtn")].map(t => {
              const r = t.getBoundingClientRect();
              const cap = t.querySelector(".cap");
              return {
                id: t.id || "(none)",
                x: Math.round(r.x),
                y: Math.round(r.y),
                w: Math.round(r.width),
                h: Math.round(r.height),
                capW: cap ? Math.round(cap.getBoundingClientRect().width) : null,
              };
            });
            if (!items.length) continue;
            const usedRight = Math.max(...items.map(i => i.x + i.w));
            out.push({
              columns: cs.gridTemplateColumns,
              gridWidth: Math.round(g.width),
              items: items,
              rows: [...new Set(items.map(i => i.y))].length,
              cols: [...new Set(items.map(i => i.x))].length,
              wasteRightPx: Math.round(g.width - (usedRight - g.x)),
              wasteRightPct: Math.round((g.width - (usedRight - g.x)) / g.width * 100),
            });
          }
          return out;
        }"""
    )
    out["tiles"] = tiles

    # Icon centring inside the overlay buttons.
    #
    # A Range over selectNodeContents(el) spans EVERY text node in the button,
    # including the screen-reader-only <span class="sr">. That span is
    # position:absolute with white-space:nowrap, so it extends to its own right
    # edge and the Range reports a box several times wider than the button. The
    # tool then reported dx=+107 px of "miscentring" for an icon that was
    # already dead centre, and blamed the CSS. This measured the technique, not
    # the layout.
    #
    # So measure the VISIBLE glyph: the span the user sees, which is the one
    # marked aria-hidden (the accessible name is the .sr span beside it).
    icons = page.evaluate(
        """() => {
          const out = [];
          for (const el of document.querySelectorAll(".streamctl .ico")) {
            const r = el.getBoundingClientRect();
            if (!r.width) continue;
            const cs = getComputedStyle(el);
            const glyph = el.querySelector('span[aria-hidden="true"]');
            const g = glyph ? glyph.getBoundingClientRect() : null;
            if (!g || !g.width) continue;
            out.push({
              id: el.id || "(none)",
              box: [Math.round(r.width), Math.round(r.height)],
              glyph: [Math.round(g.width), Math.round(g.height)],
              glyph_text: glyph.textContent,
              dx: Math.round((g.left + g.width / 2) - (r.left + r.width / 2)),
              dy: Math.round((g.top + g.height / 2) - (r.top + r.height / 2)),
              display: cs.display,
              align: cs.alignItems,
              justify: cs.justifyContent,
            });
          }
          return out;
        }"""
    )
    out["overlay_icons"] = icons
    out["overlay_icons_centred"] = all(
        abs(i["dx"]) <= 2 and abs(i["dy"]) <= 2 for i in icons
    ) if icons else None

    # Dropdowns must fit their content, not the full column.
    selects = page.evaluate(
        """() => {
          const out = [];
          for (const el of document.querySelectorAll("select")) {
            const r = el.getBoundingClientRect();
            const parent = el.parentElement;
            const pr = parent ? parent.getBoundingClientRect() : null;
            out.push({
              id: el.id,
              width: Math.round(r.width),
              parentWidth: pr ? Math.round(pr.width) : null,
              fillsParent: pr ? r.width > pr.width * 0.95 : null,
            });
          }
          return out;
        }"""
    )
    out["selects"] = selects
    out["selects_oversized"] = [s["id"] for s in selects if s["fillsParent"]]

    # Any light background anywhere would be a new white ball.
    out["light_backgrounds"] = page.evaluate(
        """() => {
          const bad = [];
          for (const el of document.querySelectorAll("*")) {
            const cs = getComputedStyle(el);
            const m = cs.backgroundColor;
            if (!m || m === "transparent" || m === "rgba(0, 0, 0, 0)") continue;
            const n = m.match(/[\\d.]+/g);
            if (!n) continue;
            const [r, g, b, a = 1] = n.map(Number);
            if (a < 0.5) continue;
            const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            if (lum > 140) {
              bad.push({
                tag: el.tagName.toLowerCase(),
                cls: (el.className || "").toString().slice(0, 40),
                bg: m,
              });
            }
          }
          return bad.slice(0, 12);
        }"""
    )

    # Visible text must be dash-free, escapes included.
    out["dashes_in_text"] = page.evaluate(
        """() => {
          const bad = [];
          const rx = /[\\u2010-\\u2015\\u2212]/;
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            const t = n.nodeValue || "";
            if (rx.test(t)) bad.push(t.trim().slice(0, 60));
          }
          return bad.slice(0, 10);
        }"""
    )
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("base", nargs="?", default="http://192.168.1.184:8080/")
    ap.add_argument("--out", default="/root/.hermes/cache/scratch/e2e")
    args = ap.parse_args()

    outdir = pathlib.Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)
    report: dict = {"base": args.base, "modes": {}}

    with sync_playwright() as p:
        browser = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage",
                                           "--autoplay-policy=no-user-gesture-required"])
        page = browser.new_page(viewport=PHONE, device_scale_factor=2,
                                has_touch=True, is_mobile=True)
        problems: list[str] = []
        page.on("pageerror", lambda e: problems.append(f"pageerror: {str(e)[:180]}"))
        page.on("console", lambda m: problems.append(f"console.{m.type}: {m.text[:160]}")
                if m.type == "error" else None)

        page.goto(args.base, wait_until="load", timeout=60000)
        page.wait_for_timeout(10000)

        # Mode 1: whatever it starts in.
        report["modes"]["as_loaded"] = measure(page)
        page.screenshot(path=str(outdir / "web_as_loaded.png"), full_page=True)

        # Mode 2: force the other stream mode so both get measured and shot.
        try:
            page.evaluate("() => localStorage.setItem('ocubea_mode','mjpeg')")
            page.reload(wait_until="load", timeout=60000)
            page.wait_for_timeout(9000)
            report["modes"]["mjpeg"] = measure(page)
            page.screenshot(path=str(outdir / "web_mjpeg.png"), full_page=True)
        except Exception as e:
            problems.append(f"mode switch failed: {str(e)[:140]}")

        report["problems"] = problems
        browser.close()

    print(json.dumps(report, indent=2, ensure_ascii=False))

    # Verdicts, from the measurements above only.
    fails = []
    # A pageerror or console error is a defect the user sees as a half-rendered
    # page, and this file collects them into report["problems"] but used to only
    # print them: the tool exited 0 with 68 identical insertBefore errors in
    # problems while reporting "OK" on every measurement. A collected error that
    # cannot fail the run is decoration.
    seen = set()
    for p in report["problems"]:
        kind, _, rest = p.partition(": ")
        key = (kind, rest[:60])
        if key in seen:
            continue
        seen.add(key)
        fails.append(f"{kind}: {rest[:120]}")
    if report["problems"]:
        fails.append(
            f"{len(report['problems'])} page/console error(s) on load, "
            f"{len(seen)} distinct -- see report['problems']"
        )
    for mode, m in report["modes"].items():
        if m.get("statusbar_above_stream") is False:
            fails.append(f"{mode}: status bar is NOT above the stream")
        if m.get("slider_has_custom_thumb") is False:
            fails.append(f"{mode}: range input has no custom thumb (white ball)")
        if m.get("overlay_icons_centred") is False:
            fails.append(f"{mode}: overlay icon glyph is off-centre")
        for bad in m.get("light_backgrounds", []):
            fails.append(f"{mode}: light background {bad['bg']} on {bad['tag']}.{bad['cls']}")
        for d in m.get("dashes_in_text", []):
            fails.append(f"{mode}: dash in visible text {d!r}")
        for sid in m.get("selects_oversized", []):
            fails.append(f"{mode}: select {sid} still fills its column")
        # A tile row is a ROW. Three correctly-sized tiles stacked in one column
        # waste 77% of the card and pass every width check, so the assertion is
        # about position, not size.
        #
        # Rows of ONE tile are exempt from the waste threshold. A single element
        # in a 370px card always leaves the rest empty, that is not a defect, and
        # measuring it made the gate red on correct layout: the solo audio toggle
        # reads 256px "wasted" (69%) and the recorder 286px (77%) purely because
        # nothing follows them. What must hold is that the tiles which HAVE
        # siblings share a row, and that a row's tracks follow its tile count
        # rather than a hardcoded three.
        for t in m.get("tiles", []):
            n = len(t["items"])
            if n >= 3 and t["cols"] < 2:
                fails.append(
                    f"{mode}: {n} icon tiles stacked in one column "
                    f"(cols={t['cols']}, rows={t['rows']}), "
                    f"{t['wasteRightPct']}% of the row wasted"
                )
            if n < 2:
                continue
            tracks = len(t["columns"].split())
            if tracks > n:
                fails.append(
                    f"{mode}: icon tile row of {n} laid out on {tracks} tracks, "
                    f"so the surplus track(s) are empty: "
                    f"{t['wasteRightPx']}px of {t['gridWidth']}px unused"
                )
    print()
    if fails:
        for f in fails:
            print(f"  FAIL {f}")
        return 1
    print("  OK: status above stream, custom slider thumb, centred icons, "
          "no light backgrounds, no dashes, selects fit their content, "
          "icon tiles in a row")
    return 0


if __name__ == "__main__":
    sys.exit(main())
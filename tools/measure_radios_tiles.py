#!/usr/bin/env python3
"""Measure the two things the screenshot report and the gate disagree about.

vision_analyze said the radio buttons are plain white circles and the icon tiles
are wide pills. tools/shoot_web_ui.py said light_backgrounds: [] and (never
measured tile width at all). Both cannot be right, and a scaled-down full-page
screenshot is not evidence either way, so read the laid-out DOM.

Also answers why they disagree if they do: an unstyled radio paints its own
appearance with no background at all, which is exactly why a
backgroundColor-based light-background scan reports nothing.
"""
from __future__ import annotations

import json
import sys

from playwright.sync_api import sync_playwright

URL = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.29:8080/"


def main() -> int:
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        pg = b.new_page(viewport={"width": 412, "height": 915})
        pg.goto(URL, wait_until="load", timeout=60000)
        pg.wait_for_timeout(9000)

        data = pg.evaluate(
            """() => {
              const out = {};

              // Radios: appearance is what decides whether the UA paints a
              // white disc. A background-color scan cannot see this, because an
              // unstyled radio has NO background of its own.
              out.radios = [...document.querySelectorAll("input[type=radio]")].map(r => {
                const cs = getComputedStyle(r);
                const rect = r.getBoundingClientRect();
                return {
                  id: r.id || "(none)",
                  name: r.name || "(none)",
                  appearance: cs.appearance,
                  webkitAppearance: cs.webkitAppearance,
                  accentColor: cs.accentColor,
                  width: Math.round(rect.width),
                  height: Math.round(rect.height),
                  background: cs.backgroundColor,
                  border: cs.border,
                  checked: r.checked,
                };
              });

              // Tiles: width vs caption width is the "wide pill" question.
              out.tiles = [...document.querySelectorAll(".tilebtn")].map(t => {
                const rect = t.getBoundingClientRect();
                const cap = t.querySelector(".cap");
                const svg = t.querySelector("svg");
                const cr = cap ? cap.getBoundingClientRect() : null;
                const sr = svg ? svg.getBoundingClientRect() : null;
                return {
                  id: t.id || "(none)",
                  width: Math.round(rect.width),
                  height: Math.round(rect.height),
                  capWidth: cr ? Math.round(cr.width) : null,
                  capText: cap ? (cap.textContent || "").trim().slice(0, 22) : null,
                  svg: sr ? [Math.round(sr.width), Math.round(sr.height)] : null,
                  // Where the icon sits relative to the tile centre.
                  svgDx: sr
                    ? Math.round((sr.left + sr.width / 2) - (rect.left + rect.width / 2))
                    : null,
                  svgDy: sr
                    ? Math.round((sr.top + sr.height / 2) - (rect.top + rect.height / 2))
                    : null,
                };
              });

              // The grid the tiles live in, and the width available to it.
              const grid = document.querySelector(".tiles");
              if (grid) {
                const cs = getComputedStyle(grid);
                out.tilesGrid = {
                  columns: cs.gridTemplateColumns,
                  display: cs.display,
                  gap: cs.gap,
                  width: Math.round(grid.getBoundingClientRect().width),
                };
              }
              const host = grid && grid.parentElement;
              if (host) {
                out.tilesHostWidth = Math.round(host.getBoundingClientRect().width);
              }

              // Widest gap between a tile and the card edge, and how much of
              // the row the tiles actually occupy.
              if (out.tiles.length) {
                const w = out.tiles.map(t => t.width);
                out.tileWidthSpread = [Math.min(...w), Math.max(...w)];
              }

              // Sliders, for comparison: appearance:none is the fix for the
              // white ball, and it should read back as such.
              out.sliders = [...document.querySelectorAll("input[type=range]")].map(r => {
                const cs = getComputedStyle(r);
                return {
                  appearance: cs.appearance,
                  webkitAppearance: cs.webkitAppearance,
                  width: cs.width,
                  height: cs.height,
                };
              });
              return out;
            }"""
        )

        pg.screenshot(path="/root/.hermes/cache/scratch/e2e/radios_tiles.png",
                      full_page=True)

        print("RADIOS (appearance decides whether the UA paints a white disc):")
        for r in data.get("radios", []):
            print(f"  {r['id']:18s} appearance={r['appearance']:7s} "
                  f"webkit={r['webkitAppearance']:7s} {r['width']}x{r['height']} "
                  f"bg={r['background']}")
            print(f"      border={r['border']}")
            print(f"      accent={r['accentColor']}  checked={r['checked']}")
        print()
        print("SLIDERS (appearance:none expected):")
        for s in data.get("sliders", []):
            print(f"  appearance={s['appearance']:7s} webkit={s['webkitAppearance']:7s} "
                  f"{s['width']}x{s['height']}")
        print()
        print("TILES:")
        for t in data.get("tiles", []):
            print(f"  {t['id']:18s} {t['width']}x{t['height']}  cap={t['capWidth']}px "
                  f"{t['capText']!r:26s} svg={t['svg']} dx={t['svgDx']} dy={t['svgDy']}")
        print()
        print(f"  rozrzut szerokosci kafelkow: {data.get('tileWidthSpread')}")
        print(f"  grid .tiles: {data.get('tilesGrid')}")
        print(f"  szerokosc rodzica kafelkow: {data.get('tilesHostWidth')}")
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
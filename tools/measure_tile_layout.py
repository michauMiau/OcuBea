#!/usr/bin/env python3
"""Are the tiles laid out in a grid, or stacked in one column?

tools/shoot_web_ui.py measured tile widths (84px) but never asked where the
tiles actually sit, so a one-column stack of 84px tiles inside a 370px container
passed every width check while wasting 286px of the row -- which is exactly the
complaint ("dużo zmarnowanego miejsca"). vision_analyze independently described
a vertical stack of pills, and getComputedStyle(grid).gridTemplateColumns
resolves to a single "84px" track.

Both can be true of a grid whose auto-fill produced one track. Measure the
positions and count them.
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

URL = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.29:8080/"


def main() -> int:
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        pg = b.new_page(viewport={"width": 412, "height": 915})
        pg.goto(URL, wait_until="load", timeout=60000)
        pg.wait_for_timeout(9000)

        for width in (360, 412, 768):
            pg.set_viewport_size({"width": width, "height": 915})
            pg.wait_for_timeout(700)
            data = pg.evaluate(
                """() => {
                  const grid = document.querySelector(".tiles");
                  if (!grid) return {error: "no .tiles"};
                  const gcs = getComputedStyle(grid);
                  const gr = grid.getBoundingClientRect();
                  const tiles = [...grid.querySelectorAll(".tilebtn")].map(t => {
                    const r = t.getBoundingClientRect();
                    return {id: t.id, x: Math.round(r.x), y: Math.round(r.y),
                            w: Math.round(r.width), h: Math.round(r.height)};
                  });
                  // A grid row = same y. Count distinct rows and columns.
                  const rows = [...new Set(tiles.map(t => t.y))].length;
                  const cols = [...new Set(tiles.map(t => t.x))].length;
                  const usedRight = tiles.length
                    ? Math.max(...tiles.map(t => t.x + t.w)) - gr.x : 0;
                  return {
                    columns: gcs.gridTemplateColumns,
                    gridWidth: Math.round(gr.width),
                    gap: gcs.gap,
                    tiles: tiles,
                    rows: rows,
                    cols: cols,
                    wasteRightPx: Math.round(gr.width - usedRight),
                    wasteRightPct: gr.width
                      ? Math.round((gr.width - usedRight) / gr.width * 100) : 0,
                  };
                }"""
            )
            print(f"=== viewport {width}px ===")
            if data.get("error"):
                print("  ", data["error"])
                continue
            print(f"  grid width        : {data['gridWidth']}px   gap {data['gap']}")
            print(f"  gridTemplateColumns: {data['columns']}")
            print(f"  kafelki           : {len(data['tiles'])}")
            print(f"  wiersze x kolumny : {data['rows']} x {data['cols']}")
            for t in data["tiles"]:
                print(f"     {t['id']:9s} x={t['x']:4d} y={t['y']:4d} {t['w']}x{t['h']}")
            print(f"  zmarnowane z prawej: {data['wasteRightPx']}px "
                  f"({data['wasteRightPct']}%)")
            print()
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
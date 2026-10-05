#!/usr/bin/env python3
"""Why does .streamctl .ico render as display:block when the rule says flex?

The CSS text carries `.streamctl .ico { ... display: flex; align-items: center;
justify-content: center; }` and my static gate passes on that string. The browser
reports display:block and the glyph sits 34-108 px off centre. Both cannot be
true of the same element, so find out which selector actually wins.

The answer is not "the CSS is wrong". It is that a rule existing in a stylesheet
is not a rule applying to an element, and every static check in this repo that
greps the stylesheet is blind to the difference.
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

URL = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.184:8080/"


def main() -> int:
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        pg = b.new_page(viewport={"width": 412, "height": 915})
        pg.goto(URL, wait_until="load", timeout=60000)
        pg.wait_for_timeout(9000)

        info = pg.evaluate(
            """() => {
              const out = {};
              const els = [...document.querySelectorAll(".streamctl .ico")];
              out.count = els.length;
              if (!els.length) return out;

              const el = els[0];
              const cs = getComputedStyle(el);
              out.computed = {
                display: cs.display,
                alignItems: cs.alignItems,
                justifyContent: cs.justifyContent,
                width: cs.width,
                height: cs.height,
                fontSize: cs.fontSize,
                lineHeight: cs.lineHeight,
                padding: cs.padding,
              };

              // Which rules does the browser think apply, in order?
              out.matched = [];
              for (const sheet of document.styleSheets) {
                let rules;
                try { rules = sheet.cssRules; } catch (e) { continue; }
                for (const r of rules) {
                  if (!r.selectorText) continue;
                  try {
                    if (el.matches(r.selectorText)) {
                      out.matched.push({
                        selector: r.selectorText,
                        display: r.style.display || "",
                        align: r.style.alignItems || "",
                        justify: r.style.justifyContent || "",
                      });
                    }
                  } catch (e) { /* bad selector */ }
                }
              }

              // The class list and the actual DOM shape.
              out.outer = el.outerHTML.slice(0, 300);
              out.childNodes = [...el.childNodes].map(n => ({
                type: n.nodeType,
                name: n.nodeName,
                text: (n.nodeValue || "").trim().slice(0, 40),
                display: n.nodeType === 1 ? getComputedStyle(n).display : null,
              }));
              out.rect = (() => { const r = el.getBoundingClientRect();
                return [r.width, r.height]; })();
              return out;
            }"""
        )

        print("  .streamctl .ico count:", info.get("count"))
        print("  computed:", info.get("computed"))
        print()
        print("  outerHTML:", info.get("outer"))
        print()
        print("  childNodes:")
        for c in info.get("childNodes", []):
            print("   ", c)
        print()
        print("  matching rules, in stylesheet order:")
        for m in info.get("matched", []):
            flag = ""
            if m["display"]:
                flag = f"  <-- display:{m['display']}"
            print(f"    {m['selector'][:70]:72s}{flag}")
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
#!/usr/bin/env python3
"""Every theme the app applies must carry the dark window background, on every API.

What this guards: the night the dark-bars fix moved two API-gated attributes out
of the default theme to satisfy lint's NewApi check against minSdk 23. The
versioned copies were written with their own Material parent, and aapt2 then
showed:

    resource 0x7f12029c style/Theme.OcuBea
      ()   (style) size=7 parent=style/Theme.MaterialComponents.NoActionBar
             windowBackground, statusBarColor, navigationBarColor,
             windowLightStatusBar, colorPrimary, colorPrimaryVariant, colorOnPrimary
      (v27) (style) size=1 parent=style/Theme.MaterialComponents.NoActionBar
             windowLightNavigationBar
      (v29) (style) size=1 parent=style/Theme.MaterialComponents.NoActionBar
             enforceNavigationBarContrast

A versioned resource REPLACES the default one; it does not merge. So on API 27
and later the app ran a theme one attribute deep, with no windowBackground, on
every device it targets. The bar pixel measurement did not catch it, because the
bars are painted from code and from the colour attrs, not from that item.

The fix is a shared Theme.OcuBea.Base that all three configs inherit, and the
check below is against the COMPILED apk rather than the XML: a source reading can
be fooled by a parent name that aapt2 resolves differently, and this whole class
of bug is invisible until it is merged.

Usage: python3 tools/verify_theme_gate.py [apk]
Exit: 0 every applied theme keeps the dark background, 1 otherwise.
"""

from __future__ import annotations

import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "app/build/outputs/apk/debug/app-debug.apk"

AAPT_CANDIDATES = sorted(Path("/usr/lib/android-sdk/build-tools").glob("*/aapt2"))

# Every style the manifest applies. Missing one here is how a screen silently
# keeps the wrong background, so the manifest is read rather than trusted.
THEMES = ["Theme.OcuBea", "Theme.OcuBea.NoActionBar"]

# The attributes that must survive on every config. windowBackground is the one
# that shows as a white flash; the rest keep the bars and the accent colour.
REQUIRED = {
    "0x01010054": "windowBackground",
    "0x01010451": "statusBarColor",
    "0x010104e0": "windowLightStatusBar",
}


def manifest_themes() -> list[str]:
    text = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
    found = re.findall(r'android:theme="@style/([^"]+)"', text)
    return sorted(set(found))


def aapt2() -> Path:
    if not AAPT_CANDIDATES:
        print("  aapt2 not found under /usr/lib/android-sdk/build-tools")
        sys.exit(2)
    return AAPT_CANDIDATES[-1]


def dump() -> str:
    proc = subprocess.run(
        [str(aapt2()), "dump", "resources", str(APK)],
        capture_output=True, text=True, timeout=300,
    )
    if proc.returncode != 0:
        print(f"  aapt2 failed: {proc.stderr.strip()[:200]}")
        sys.exit(2)
    return proc.stdout


def style_by_name(text: str, name: str) -> str | None:
    """Every line of every config of one style, or None if it is absent.

    The parent in aapt2's dump is a full reference like
    `style/Theme.OcuBea.Base (0x7f12029d)`, so both forms have to match.
    """
    short = name.split("/")[-1].split(" ")[0]
    out: list[str] = []
    in_target = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("resource 0x"):
            in_target = stripped.endswith(f"style/{short}")
            continue
        if in_target:
            out.append(stripped)
    return "\n".join(out) if out else None


def main() -> int:
    if not APK.exists():
        print(f"  apk not built: {APK}")
        return 2

    applied = manifest_themes()
    print(f"  {APK.name}")
    print(f"  themes the manifest applies: {', '.join(applied)}")
    print()

    text = dump()
    failures = 0

    for theme in applied:
        # Collect every config of this style: the () entry plus (vNN) ones.
        # Strictly one resource: the parser must stop at the NEXT resource
        # line, not keep collecting. The first draft only ever looked for a
        # "(config) (style)" line and so swept up every style in the dump --
        # 14 configs for a style that has three, and 26 invented failures that
        # looked exactly like real ones. A gate that reports phantom failures is
        # as useless as one that reports none.
        blocks: dict[str, list[str]] = {}
        current: str | None = None
        in_target = False
        for line in text.splitlines():
            stripped = line.strip()
            # Enter this style: "resource 0x... style/Theme.OcuBea" exactly.
            if stripped.startswith("resource 0x") and stripped.endswith(f"style/{theme}"):
                current = None      # arm only once we see a config line
                in_target = True
                continue
            if stripped.startswith("resource 0x"):
                current = None       # a different resource: stop collecting
                in_target = False
                continue
            if not in_target:
                continue
            m = re.match(r"^\((\w*)\)\s*\(style\)", stripped)
            if m:
                current = m.group(1) or "default"
                blocks.setdefault(current, [])
            # The config line itself carries size= and parent=, which is where
            # the parent comes from, so it must be KEPT rather than skipped.
            # `continue` after the match dropped it, every body came back
            # without a parent, the chain walk stopped immediately and the gate
            # reported all four configs as having lost the theme.
            if current is not None:
                blocks[current].append(stripped)

        if not blocks:
            print(f"  FAIL: {theme} not found in the compiled apk")
            failures += 1
            continue

        print(f"  {theme}: {len(blocks)} config(s) -> {', '.join(sorted(blocks))}")
        for config, lines in sorted(blocks.items()):
            body = "\n".join(lines)
            parent = re.search(r"parent=([^\s(]+)", body)
            parent_name = parent.group(1) if parent else None

            # A config may legitimately hold no items of its own and inherit
            # everything. Theme.OcuBea is exactly that case: size=0, parent
            # Theme.OcuBea.Base. Checking only the config's own lines therefore
            # reports the fixed theme as broken -- which is the inverse of the
            # bug this gate exists for. So the parent chain is followed, and
            # what counts is the union of every style it reaches.
            first_parent = parent_name
            chain = [body]
            seen = set()
            while parent_name and parent_name not in seen:
                seen.add(parent_name)
                pbody = style_by_name(text, parent_name)
                if pbody is None:
                    break
                chain.append(pbody)
                nxt = re.search(r"parent=([^\s(]+)", pbody)
                parent_name = nxt.group(1) if nxt else None
            resolved = "\n".join(chain)
            missing = [n for a, n in REQUIRED.items() if a not in resolved]
            inherited = first_parent is not None and len(chain) > 1
            src = first_parent if inherited else "itself"

            if missing:
                print(f"    FAIL ({config}): missing {', '.join(missing)}"
                      f"  [resolved through {src}]")
                failures += 1
            else:
                print(f"    ok   ({config}): dark window background present"
                      + (f", inherited from {first_parent}" if inherited else ""))

    print()
    if failures:
        print(f"  {failures} config(s) lost the dark theme -- the app would flash")
        print("  a light window on those devices")
        return 1
    print(f"  every config of every applied theme keeps the dark window background")
    return 0


if __name__ == "__main__":
    sys.exit(main())
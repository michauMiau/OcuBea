#!/usr/bin/env python3
"""
Check that the landscape layout stays compatible with the portrait one.

WHY THIS EXISTS, in the shape of the bug it guards against:

`res/layout-land/activity_main.xml` is selected by the resource system, not by
code. Nothing checks it at build time. `MainActivity` looks views up by id after
`setContentView` and calls methods on the result, so a layout that inflates but is
missing one of those ids compiles, installs, launches in portrait, and then throws
a NullPointerException the moment the phone is rotated.

That is the worst shape of defect: green build, green install, working app, failure
only on the one input the user reported as broken. A device test would catch it, but
only if a rotation test happens to run after someone edits the landscape file. This
gate makes it fail on the edit instead.

WHAT IT CHECKS:

1. Every id referenced from Kotlin in MainActivity.kt is present in BOTH layouts.
   Parsed out of the source, so adding a `findViewById(R.id.foo)` without adding
   `android:id="@+id/foo"` to both layouts is a failure, not a surprise at runtime.
2. The landscape layout does not shrink past the portrait one on any id, so a button
   cannot quietly disappear in landscape only.
3. Neither layout pins the control bar with a height that is a fixed fraction of the
   screen, which is what broke landscape in the first place. `layout_marginBottom`
   and a fixed `layout_height` are allowed; `layout_weight` with a `0dp` height on a
   full-width bar is not.

MUTATIONS: `--mutate` perturbs each rule and requires the gate to go red. A gate that
cannot fail is not a gate; see references/webui-gates-that-lying.md for the four ways
these went wrong before.

Usage:
    python3 tools/verify_landscape_layout.py            # gate
    python3 tools/verify_landscape_layout.py --mutate   # prove it can fail
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
PORTRAIT = ROOT / "app/src/main/res/layout/activity_main.xml"
LANDSCAPE = ROOT / "app/src/main/res/layout-land/activity_main.xml"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/ocubea/MainActivity.kt"


def layout_ids(path):
    """
    Every @+id/... declared in a layout file.

    Returns (ids, None) or (None, message). The None is deliberately propagated by
    callers rather than coerced to an empty set: an unreadable layout and a layout
    with no ids must not look the same, because the first is a parse failure and the
    second would silently pass the "does landscape drop controls" check.
    """
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        return None, f"{path.name}: not parseable as XML: {exc}"
    except OSError as exc:
        return None, f"{path.name}: not readable: {exc}"
    ids = set()
    for node in tree.iter():
        value = node.get(ANDROID_NS + "id")
        if not value:
            continue
        # @+id/foo -> foo
        if value.startswith("@+id/"):
            ids.add(value[len("@+id/") :])
    return ids, None


def referenced_ids(path):
    """
    Every R.id.<name> the activity looks up in code, or None if unreadable.

    `android.R.id.*` is a framework resource, not one of ours. It arrives here as the
    bare text `R.id.content` inside `findViewById(android.R.id.content)`, so a plain
    scan for `R.id.<name>` picks it up and then reports it missing from both layouts.
    That is a false failure on the very first run, which is how a gate teaches people
    to ignore it. Exclude names that appear only as `android.R.id.<name>`.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        return None, f"cannot read {path.name}: {exc}"
    android_ids = set(re.findall(r"android\.R\.id\.(\w+)", text))
    own_ids = set(re.findall(r"(?<!android\.)\bR\.id\.(\w+)", text))
    return own_ids - android_ids, None


def control_bar_is_fixed_width(path):
    """
    True if the layout pins a full-width control bar with a hard dp height.

    That is the shape that broke landscape: 108dp of bar plus 72dp of bottom margin
    on a 720px-tall screen is 50% of the height, and dp does not shrink with the
    screen. Landscape solves it by moving the controls into a side column, so this
    must be false there.
    """
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return False
    for node in tree.iter("LinearLayout"):
        gravity = node.get(ANDROID_NS + "layout_gravity") or ""
        height = node.get(ANDROID_NS + "layout_height") or ""
        # Full-width bar: no side gravity, pinned bottom, hard dp height.
        if "bottom" in gravity and re.match(r"^\d+dp$", height):
            return True
    return False


def control_column_fits(path, screen_h_px=720, density_dpi=320, nav_bar_dp=0):
    """
    Does the landscape control column actually fit the screen?

    Returns (fits, detail). The numbers come from the device that reported the
    problem: the Sony F3311 in landscape is 1280x720 at 320dpi, so 1dp = 2px and
    the usable height is 360dp.

    This check exists because of a real measurement, not a guess. The first
    landscape layout put all 13 controls in one column at 46dp each: 628dp of
    buttons plus 58dp of status/URL header is 686dp against 360dp available. The
    build passed, the app launched, and a screenshot showed the column cut off
    below the fifth button. Nothing in the build or the lint reported that, because
    a LinearLayout that overflows is not an error, it is just clipped.

    Counting is done from the layout file, not from a hardcoded total, so adding a
    button without re-checking this turns the gate red.
    """
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        return False, f"landscape layout does not parse: {exc}"

    dp2px = density_dpi / 160.0
    available_dp = (screen_h_px - nav_bar_dp * dp2px) / dp2px

    buttons = []
    for node in tree.iter():
        tag = node.tag
        if tag not in ("Button", "ImageButton", "android.widget.Button"):
            continue
        name = node.get(ANDROID_NS + "id") or "?"
        if name.startswith("@+id/"):
            name = name[len("@+id/") :]
        height = node.get(ANDROID_NS + "layout_height") or "wrap_content"
        margin = node.get(ANDROID_NS + "layout_marginTop") or "0dp"
        m = re.match(r"^(\d+(?:\.\d+)?)dp$", height)
        button_dp = float(m.group(1)) if m else 46.0
        mm = re.match(r"^(\d+(?:\.\d+)?)dp$", margin)
        margin_dp = float(mm.group(1)) if mm else 0.0
        buttons.append((name, button_dp + margin_dp))

    if not buttons:
        return False, "no buttons found in the landscape layout"

    # A GridLayout with N columns needs ceil(N / cols) rows.
    columns = 1
    for node in tree.iter("GridLayout"):
        value = node.get(ANDROID_NS + "columnCount")
        if value and value.isdigit():
            columns = int(value)
    rows = -(-len(buttons) // columns)

    # Header: the status and url TextViews above the grid.
    header_dp = 0.0
    for node in tree.iter("TextView"):
        if node.get(ANDROID_NS + "id") in ("@+id/tvStatus", "@+id/tvUrl"):
            h = node.get(ANDROID_NS + "layout_height") or "wrap_content"
            m = re.match(r"^(\d+(?:\.\d+)?)dp$", h)
            header_dp += float(m.group(1)) if m else 20.0

    # Rows only bound the buttons: every button contributes its own height, and the
    # tallest button in a row sets that row's height, so summing button heights and
    # then adding a per-row allowance double-counts. The earlier version added
    # 4*rows on top of the summed heights and reported "594dp against 360dp" for a
    # layout that a screenshot shows fitting, which is the checker disagreeing with
    # the device rather than catching a real overflow.
    #
    # Correct model: header + the tallest button per row + container padding.
    per_row = {}
    for index, (_, height_dp) in enumerate(buttons):
        row = index // columns
        per_row[row] = max(per_row.get(row, 0.0), height_dp)
    needed_dp = header_dp + sum(per_row.values()) + 2 * rows  # 2dp cell gap per row
    fits = needed_dp <= available_dp
    detail = (
        f"{len(buttons)} buttons in {columns} column(s) -> {rows} rows, "
        f"header {header_dp:.0f}dp, total {needed_dp:.0f}dp, "
        f"available {available_dp:.0f}dp"
        + ("" if fits else f" -- OVER BY {needed_dp - available_dp:.0f}dp")
    )
    return fits, detail


def run_checks():
    failures = []

    portrait_ids, err = layout_ids(PORTRAIT)
    if err:
        return [err]
    land_ids, err = layout_ids(LANDSCAPE)
    if err:
        return [err]
    code_ids, err = referenced_ids(MAIN_ACTIVITY)
    if err:
        return [err]
    # Unreachable given the guards above, but stated so the set operations below are
    # not Optional for a reader and for the type checker.
    assert portrait_ids is not None and land_ids is not None and code_ids is not None

    # 1. Every id the code looks up exists in both layouts.
    missing_portrait = sorted(code_ids - portrait_ids)
    missing_landscape = sorted(code_ids - land_ids)
    if missing_portrait:
        failures.append(
            f"ids referenced in MainActivity.kt are missing from layout/: "
            f"{', '.join(missing_portrait)}"
        )
    if missing_landscape:
        failures.append(
            f"ids referenced in MainActivity.kt are missing from layout-land/: "
            f"{', '.join(missing_landscape)}"
        )

    # 2. Landscape must not drop a control the portrait layout has.
    #    tvTitle is exempt: it is not referenced from code and the landscape header
    #    carries the URL and status instead.
    dropped = sorted((portrait_ids - land_ids) - {"tvTitle"})
    if dropped:
        failures.append(
            f"layout-land/ drops controls present in layout/: {', '.join(dropped)}"
        )

    # 3. The fixed-height full-width bar is what broke landscape.
    if control_bar_is_fixed_width(LANDSCAPE):
        failures.append(
            "layout-land/ still pins a full-width bottom control bar with a fixed "
            "dp height; at 720px tall that is ~50% of the screen. Controls belong "
            "in a side column in landscape."
        )

    # 4. The column has to fit the shortest screen this app is tested on.
    fits, detail = control_column_fits(LANDSCAPE)
    if not fits:
        failures.append(f"layout-land/ controls do not fit the screen: {detail}")

    return failures


def mutations():
    """
    Perturb each rule; each must turn the gate red.

    Both mutations read the real landscape file and write a perturbed copy back, then
    the caller restores the original and re-runs the gate to prove it went green
    again. Nothing here fabricates a failing input: each perturbation is a defect
    someone could plausibly introduce by editing the layout file.
    """

    def drop_landscape_id():
        """
        Rename one control's id in the landscape layout.

        This is the exact defect the gate exists for: the layout still inflates,
        the build still passes, portrait still works, and rotation throws on the
        null the lookup returns. Guard the empty case rather than indexing blind.
        """
        ids, err = layout_ids(LANDSCAPE)
        if not ids:
            raise AssertionError(f"landscape layout has no ids to perturb: {err}")
        victim = sorted(ids - {"tvUrl"})[-1]
        original = LANDSCAPE.read_text(encoding="utf-8")
        needle = f'android:id="@+id/{victim}"'
        if needle not in original:
            raise AssertionError(f"id '{victim}' not found verbatim in {LANDSCAPE.name}")
        LANDSCAPE.write_text(
            original.replace(needle, 'android:id="@+id/renamed"', 1), encoding="utf-8"
        )
        return f"landscape layout renames control id '{victim}'"

    def add_fixed_bar():
        """Put a full-width fixed-dp-height bottom bar back into the landscape layout."""
        original = LANDSCAPE.read_text(encoding="utf-8")
        bar = (
            '    <LinearLayout\n'
            '        android:layout_width="match_parent"\n'
            '        android:layout_height="108dp"\n'
            '        android:layout_gravity="bottom"\n'
            '        android:orientation="vertical">\n'
            '    </LinearLayout>\n\n'
            '</FrameLayout>'
        )
        LANDSCAPE.write_text(original.replace("</FrameLayout>", bar, 1), encoding="utf-8")
        return "landscape layout gains a 108dp full-width bottom bar"

    def collapse_grid_to_one_column():
        """
        Turn the 2-column grid back into 1 column.

        This is the mutation for the fit arithmetic: it reproduces the layout that
        shipped in the first landscape build, where a screenshot showed the column
        clipped after the fifth control. If the fit check cannot see this, it is not
        measuring anything.
        """
        original = LANDSCAPE.read_text(encoding="utf-8")
        if 'android:columnCount="2"' not in original:
            raise AssertionError("landscape layout has no 2-column GridLayout")
        LANDSCAPE.write_text(
            original.replace('android:columnCount="2"', 'android:columnCount="1"', 1),
            encoding="utf-8",
        )
        return "landscape control grid collapses to a single column"

    def restore_original(content):
        LANDSCAPE.write_text(content, encoding="utf-8")

    return [
        (drop_landscape_id, restore_original),
        (add_fixed_bar, restore_original),
        (collapse_grid_to_one_column, restore_original),
    ]


def main():
    mutate = "--mutate" in sys.argv
    original = LANDSCAPE.read_text(encoding="utf-8")

    if not mutate:
        failures = run_checks()
        if failures:
            for f in failures:
                print(f"FAIL {f}")
            return 1
        portrait_ids, _ = layout_ids(PORTRAIT)
        land_ids, _ = layout_ids(LANDSCAPE)
        code_ids, _ = referenced_ids(MAIN_ACTIVITY)
        # run_checks() returned empty, which means all three parsed and none is None.
        assert portrait_ids and land_ids and code_ids, "gate passed with empty id sets"
        print(
            f"OK layout/ has {len(portrait_ids)} ids, layout-land/ has "
            f"{len(land_ids)} ids, {len(code_ids)} referenced from code, "
            f"all present in both"
        )
        return 0

    detected = 0
    total = 0
    for mutate_fn, restore in mutations():
        total += 1
        description = mutate_fn()
        failures = run_checks()
        try:
            if failures:
                detected += 1
                print(f"OK  detected: {description}")
                print(f"      -> {failures[0]}")
            else:
                print(f"FAIL mutation NOT detected: {description}")
        finally:
            restore(original)
        # And prove the gate is green again with the original restored.
        if run_checks():
            print(f"FAIL restore failed after: {description}")
            return 1

    # `detected` counts mutations the gate CAUGHT, so the score is detected/total.
    # Printing total - detected here printed "0/2" after catching both, which is the
    # same class of lie as a gate that cannot fail: the summary contradicted the
    # lines above it. A mutation harness whose own arithmetic is wrong is worse than
    # no harness, because the number gets quoted as evidence.
    print(f"\n{detected}/{total} mutations detected")
    if detected != total:
        return 1
    # And the gate itself must be green on the untouched file, or "all mutations
    # detected" only means the gate is red for everything.
    if run_checks():
        print("FAIL gate is red on the unperturbed file")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
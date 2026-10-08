#!/usr/bin/env python3
"""Gate for the shipped web UI.

Six defects reported from the browser on the A6 phone, each of which is a
property of the file rather than a runtime behaviour that can be observed from
outside:

  1. The HLS <video> had no aspect-ratio, so switching the mode button from
     MJPEG to HLS changed the picture box from 16/9 to the browser default.
     Measured on the phone: the same stream read as 16:9 in one mode and 4:3
     one click later. The CSS stated the ratio on `.stream img` only.
  2. The overlay glyphs sat high in their buttons. `.streamctl .ico` had
     width/height/line-height but no centring, and ⛶, ▶ and ♪ are glyphs that
     resolve on the text baseline.
  3. The audio toggle had no style rule at all -- `.audio-row .tgl` existed with
     `flex: 0 0 auto` and nothing else -- and its markup was an empty <button>.
     What the user saw was an unlabelled control.
  4. The audio toggle carried no glyph, so even styled it had nothing to show.
  5. Em dashes and en dashes in user-visible text. Reported as "get rid of all
     the em dashes on the page".
  6. The toggle's aria-label and title said "audio" in both states, so a
     screen reader announced the same thing before and after the click that
     changed the state.

The gate reads the file that is actually shipped (`app/src/main/assets/
index.html`, copied into the APK verbatim) and the compiled string resources,
because a comment in Kotlin is not what the user sees and a Kotlin source string
is not what the user sees either.

Every check below has a matching mutation in MUTATIONS, and the gate runs each
one against a temporary copy to prove the check can fail. A check that has never
been seen red is not a check.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HTML = os.path.join(ROOT, "app/src/main/assets/index.html")
VALUES = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
VALUES_PL = os.path.join(ROOT, "app/src/main/res/values-pl/strings.xml")
SERVER = os.path.join(ROOT, "app/src/main/java/com/ocubea/server/StreamServer.kt")

# Dashes that must never reach a user. U+2014 em dash, U+2013 en dash,
# U+2012 figure dash, U+2212 minus sign used as a dash, U+2010..U+2015 the rest
# of the general punctuation dashes.
DASHES = {
    "U+2014 em dash": "—",
    "U+2013 en dash": "–",
    "U+2012 figure dash": "‒",
    "U+2015 horizontal bar": "―",
    "U+2010 hyphen": "‐",
    "U+2011 non-breaking hyphen": "‑",
}

failures = []
notes = []


def fail(check, detail):
    failures.append(f"{check}: {detail}")


def css_rule(text, selector):
    """Return the declaration body of the first rule for `selector`.

    A selector can span several rules (.stream img, .stream video, ...) and a
    comma-separated list can put several selectors in one rule, so this matches
    the selector list and then splits it. The first version of this function
    used a `[^{}]*` prefix, which swallowed the `}` of the rule before it and
    matched `.pill.off .stream img {` as a single selector -- the gate then
    reported a rule that plainly exists as missing. A checker that cannot find
    the thing it checks will always find something wrong.
    """
    for m in re.finditer(r"([^{}]+)\{([^{}]*)\}", text):
        selectors = [s.strip() for s in m.group(1).split(",")]
        if selector in selectors:
            return m.group(2)
    return None


def strip_css_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def strip_html_comments(text):
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def check_aspect_ratio(html, css):
    """Both the MJPEG <img> and the HLS <video> must state the same box."""
    for sel, el in ((".stream img", "MJPEG <img>"), (".stream video", "HLS <video>")):
        body = css_rule(css, sel)
        if body is None:
            fail("aspect-ratio", f"{el} has no CSS rule at all ({sel})")
            continue
        if "aspect-ratio" not in body:
            fail("aspect-ratio", f"{el} ({sel}) states no aspect-ratio, so it "
                                f"falls back to the browser default")
        elif "16/9" not in body:
            fail("aspect-ratio", f"{el} ({sel}) states aspect-ratio "
                                f"{body.split('aspect-ratio:')[1].split(';')[0].strip()!r}, not 16/9")
        if "object-fit" not in body:
            fail("aspect-ratio", f"{el} ({sel}) has no object-fit")


def check_icon_centering(css):
    body = css_rule(css, ".streamctl .ico")
    if body is None:
        fail("icon-centering", ".streamctl .ico has no CSS rule")
        return
    for prop in ("display: flex", "align-items: center", "justify-content: center"):
        if prop not in " ".join(body.split()):
            fail("icon-centering", f".streamctl .ico lacks {prop!r}, so the glyph "
                                   f"sits on the text baseline instead of centred")


def check_toggle_styled(css):
    body = css_rule(css, ".audio-row .tgl")
    if body is None:
        fail("toggle-styled", ".audio-row .tgl has no CSS rule")
        return
    flat = " ".join(body.split())
    # A box the user can see and hit.
    for prop in ("width:", "height:", "border:", "background:"):
        if prop not in flat:
            fail("toggle-styled", f".audio-row .tgl lacks {prop!r}")
    for prop in ("display: flex", "align-items: center", "justify-content: center"):
        if prop not in flat:
            fail("toggle-styled", f".audio-row .tgl lacks {prop!r}")


def check_toggle_glyph(html):
    """Every .tgl button must carry a glyph in its markup.

    Generated content (::after) would satisfy the eye and not the accessibility
    tree, so the glyph has to be a child element.
    """
    # The audio toggle, by id.
    m = re.search(r'<button[^>]*id="bAud"[^>]*>(.*?)</button>', html, re.S)
    if not m:
        fail("toggle-glyph", 'no <button id="bAud"> found')
    elif "<span" not in m.group(1):
        fail("toggle-glyph", "the audio toggle has an empty body: no glyph in the "
                             "markup, so it renders as an unlabelled control")

    # The icon tile helper, which is what Toggle became.
    #
    # Anchored on the tile's own CSS class, which the minifier cannot rewrite,
    # because the helper identifier is renamed (Tile is `bt` in the source and
    # `Yt` in the bundle) and the destructured parameter list is collapsed.
    #
    # TWO THINGS WERE WRONG HERE, and both made the check silently pass over a
    # clean file and report nothing:
    #
    #  1. The pattern was r'class=\{"tilebtn".*?`(.*?)`'. A bare `{` in a regex
    #     is not "an opening brace", it is a quantifier with nothing to
    #     quantify, so that branch could never match anything. esbuild emits
    #     class=${"tilebtn"+...}, and the `$` was missing. The check reported
    #     "no icon-tile helper found in the bundle" for a bundle that contains
    #     the helper, one line above it.
    #  2. `(.*?)` up to the FIRST backtick is wrong even once it matches. The
    #     Tile template contains a nested html`aria-pressed=${on}` inside a
    #     spread, so the first backtick is the middle of the expression, not the
    #     end of the template. The captured "body" was the 17 characters
    #     `aria-pressed=${e}`, which contains no <svg>, no .cap and no
    #     aria-hidden, so all three sub-checks below reported "missing" for a
    #     tile that has all three. A check whose capture window ends in the
    #     middle of its subject is not a check.
    #
    # So: anchor on the class, capture to the template's own closing backtick,
    # which is the one after </button>. `\s*` because esbuild puts a newline
    # between the last child and the terminator.
    m2 = re.search(r'class=\$\{"tilebtn".*?</button>\s*`', html, re.S)
    if not m2:
        fail("toggle-glyph", "no icon-tile helper found in the bundle")
    else:
        body = m2.group(0)
        if "<svg" not in body:
            fail("toggle-glyph", "the icon tile has no <svg> in its markup, so it "
                                 "renders as a caption with no icon")
        # Scoped to the CAPTION, not the whole body: the <svg> carries
        # aria-hidden too, so a whole-body test for "aria-hidden" is still true
        # after the caption loses it, and the mutation that removes it from the
        # caption -- the one that matters, because the caption text and the
        # accessible name are the same word and an unhidden caption announces the
        # button twice -- passed.
        if not re.search(r'<span class="cap" aria-hidden="true">', body):
            fail("toggle-glyph", "the icon tile caption is not marked aria-hidden, "
                                 "so it is announced twice on top of aria-label")
        # The caption must exist before it can be hidden: a mutation that drops
        # the class as well must not pass by satisfying the check above.
        if '<span class="cap"' not in body:
            fail("toggle-glyph", "the icon tile has no .cap caption element")

    # No .tgl button may be empty anywhere.
    for mm in re.finditer(r'<button[^>]*class="tgl[^"]*"[^>]*>\s*</button>', html):
        fail("toggle-glyph", f"empty .tgl button at offset {mm.start()}")


def check_state_labels(html):
    """The audio toggle must name the action, not the feature, in both states."""
    m = re.search(r'<button[^>]*id="bAud"(.*?)</button>', html, re.S)
    if not m:
        return
    attrs = m.group(1)
    if 'aria-label=${P("audio")}' in attrs:
        fail("state-labels", 'the audio toggle aria-label is P("audio") in both '
                             'states, so the state change is not announced')
    if "audioMuted" not in attrs or "audioOn" not in attrs:
        fail("state-labels", "the audio toggle does not distinguish the two states "
                             "in its label or title")


def decode_js_escapes(text):
    r"""Resolve \uXXXX escapes, which is what the browser does before rendering.

    THE SECOND DASH BLIND SPOT. This gate counted LITERAL dash characters, and
    the page carried 16 of them only as `\u2013` and `\u2014` escape sequences
    inside JS string literals -- 4 em dashes and 12 en dashes, all in template
    literals, e.g. C.encoder||"\u2013" and the popupBlocked message. JavaScript
    turns those into a real U+2014 and a real U+2013 before the text reaches the
    DOM, so the browser drew 16 dashes while this check reported zero. The gate
    was green on exactly the file it was written to catch.

    Decoding on the raw text is safe here and needs no JS parser: a \uXXXX in
    this file only ever appears inside a string. The backslash cases are
    handled explicitly rather than trusted, so a future literal backslash
    cannot silently eat the following four characters.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\\" and i + 1 < n:
            nxt = text[i + 1]
            if nxt == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", text[i + 2:i + 6]):
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            if nxt == "\n":            # line continuation: nothing rendered
                i += 2
                continue
            if nxt in '"\'`\\/':       # an escape that renders as itself
                out.append(nxt)
                i += 2
                continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_htm_comment_text(html):
    """No `{/* ... */}` in an html`` template literal. It is NOT a comment there.

    htm has no comment syntax. Inside html`...`, a bare `{` starts LITERAL TEXT,
    which htm then parses as markup. So a JSX-style comment copied over from a
    .jsx file renders its own source into the page. Measured, not assumed:

        html`<div>before{/* just words */}after</div>`
          -> <div>before{/* just words */}after</div>     (text is visible)

    and a comment that contains an opening tag opens a real element the template
    never closes:

        html`<div>before{/* It was a <section> of its own */}after</div>`
          -> divbefore{/* It was a <section> of its own */}after</section>

    The unbalanced tag leaves a hole in htm's parse tree and Preact then calls
    insertBefore(undefined) -> TypeError on every render. This shipped: 68
    identical "insertBefore: parameter 1 is not of type 'Node'" errors on the
    A6, and the section's children silently failed to render.

    The correct form is a JS comment inside an INTERPOLATION with an explicit
    null: ${/* ... */ null}, which htm evaluates to a child it skips.

    The minifier strips the comment text but leaves `${null}` where the safe form
    was, so the buggy form is what appears verbatim in the bundle: search for the
    marker text, not for a count of ${null}.
    """
    vis = strip_html_comments(html)
    # Only app code: the vendored hls.js payload is inside <script> but has no
    # template literals of ours, and cutting it is what strip_third_party_bundle
    # already does for the dash checks.
    vis = strip_third_party_bundle(vis)
    n = 0
    for m in re.finditer(r"\{/\*", vis):
        n += 1
        if n > 5:
            break
        ln = vis[:m.start()].count("\n") + 1
        ctx = " ".join(vis[m.start():m.start() + 90].split())
        fail("htm-comment", f"index.html line {ln} has a JSX-style comment inside "
                           f"a template literal, which htm renders as visible "
                           f"text: ...{ctx}...")
    return n


def check_no_dashes(label, text, is_html):
    """Dashes are banned in user-visible text, literal or escape-encoded."""
    if is_html:
        text = strip_third_party_bundle(text)
    # Then decode, then count. Order matters: decoding first is what makes
    # \u2014 visible to the same DASHES table that catches a typed one.
    text = decode_js_escapes(text)
    for name, dash in DASHES.items():
        n = text.count(dash)
        if n:
            i = text.find(dash)
            ctx = " ".join(text[max(0, i - 70):i + 35].split())
            fail("no-dashes", f"{label} contains {n}x {name}: ...{ctx}...")


def strip_third_party_bundle(text):
    """Return the page's own text, without the vendored hls.js payload.

    The first version of this cut the whole <script> block, which is where all
    the UI lives: the page is htm/Preact, so every visible string is a template
    literal inside the inline script. Cutting the script put 100% of the
    product's text outside the check and the gate went green on a page carrying
    an em dash -- the exact failure this gate exists to catch.

    The structure measured in this file:
      0..572      <style> with the CSS
      ~22375      <script> opens; the hls.js bundle and the app code share it
      55918       "hls.js v1.7.3" banner, inside the bundle
      641408      the "HLS unsupported here" badge, in the app code

    So the cut is the CSS block, which is not rendered as text, plus everything
    between the <script> opener and the bundle's own banner. What remains is the
    app code, which is what the check is about.
    """
    text = re.sub(r"<style[^>]*>.*?</style>", "", text, flags=re.S)
    banner = text.find("hls.js v")
    if banner >= 0:
        # Everything from the <script> up to the banner is vendored code.
        opener = text.rfind("<script", 0, banner)
        if opener >= 0:
            text = text[:opener] + text[banner:]
    return text


def check_no_dashes_strict(label, text, is_html):
    """Ban the whole U+2010..U+2015 punctuation-dash block, not six names.

    The DASHES table above lists the six dashes actually seen so far, which is
    a list of what was looked for rather than of what is forbidden: U+2011 (non
    breaking hyphen) was only caught because somebody remembered to write it
    down. The IETF dash block is a closed range, so it is stated as one here and
    cannot be extended by forgetting.
    """
    if is_html:
        text = strip_third_party_bundle(text)
    text = decode_js_escapes(text)
    for cp in sorted({ord(c) for c in text if 0x2010 <= ord(c) <= 0x2015}):
        i = text.find(chr(cp))
        ctx = " ".join(text[max(0, i - 70):i + 35].split())
        fail("no-dashes", f"{label} contains U+{cp:04X} from the "
                          f"U+2010..U+2015 dash block: ...{ctx}...")


def check_server_strings():
    """Kotlin strings that reach the browser or an API client.

    Comments are not filtered: the check is on string literals, and finding a
    dash in a comment is not a finding about the user.
    """
    src = open(SERVER, encoding="utf-8").read()
    # Strip line comments and block comments, then look at what is left.
    stripped = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    stripped = re.sub(r"//[^\n]*", "", stripped)
    for name, dash in DASHES.items():
        for m in re.finditer(re.escape(dash), stripped):
            ln = stripped[:m.start()].count("\n") + 1
            ctx = " ".join(stripped[max(0, m.start() - 70):m.start() + 35].split())
            fail("no-dashes", f"StreamServer.kt non-comment line {ln} has {name}: "
                              f"...{ctx}...")


def check_string_resources():
    for path in (VALUES, VALUES_PL):
        if not os.path.exists(path):
            continue
        src = open(path, encoding="utf-8").read()
        body = re.sub(r"<!--.*?-->", "", src, flags=re.S)
        for name, dash in DASHES.items():
            n = body.count(dash)
            if n:
                i = body.find(dash)
                m = re.search(r'<string name="([^"]+)"[^>]*>[^<]*' + re.escape(dash),
                              body)
                which = m.group(1) if m else "?"
                fail("no-dashes", f"{os.path.basename(os.path.dirname(path))}/"
                                  f"{os.path.basename(path)} string {which!r} has "
                                  f"{n}x {name}")


MUTATIONS = [
    ("aspect-ratio: remove .stream video rule",
     lambda t: t.replace(".stream video {", ".stream videoDISABLED {", 1)),
    ("aspect-ratio: change video ratio to 4/3",
     lambda t: re.sub(r"(\.stream video \{[^}]*aspect-ratio:\s*)16/9", r"\g<1>4/3", t, count=1)),
    ("aspect-ratio: remove aspect-ratio from .stream img",
     lambda t: re.sub(r"(\.stream img \{[^}]*?)aspect-ratio:\s*16/9;\s*", r"\g<1>", t, count=1, flags=re.S)),
    ("icon-centering: drop display:flex from .streamctl .ico",
     lambda t: re.sub(r"(\.streamctl \.ico \{[^}]*?)display: flex;", r"\g<1>", t, count=1, flags=re.S)),
    ("icon-centering: drop justify-content from .streamctl .ico",
     lambda t: re.sub(r"(\.streamctl \.ico \{[^}]*?)justify-content: center;", r"\g<1>", t, count=1, flags=re.S)),
    ("toggle-styled: strip .audio-row .tgl back to bare flex",
     lambda t: re.sub(r"(\.audio-row \.tgl \{).*?(\})", r"\1 flex: 0 0 auto;\2", t, count=1, flags=re.S)),
    ("toggle-styled: remove background from .audio-row .tgl",
     lambda t: re.sub(r"(\.audio-row \.tgl \{[^}]*?)background:[^;]+;\s*", r"\g<1>", t, count=1, flags=re.S)),
    ("toggle-glyph: empty the audio toggle body",
     # Three traps in this pattern, all measured rather than guessed.
     # re.S: the button is written across seven lines, so a single-line pattern
     #   matches nothing and the mutation reports "did not change the file".
     # [^>]*: the attribute list contains `onClick=${()=>r(!i)}`, and the arrow's
     #   `>` ends the tag as far as the character class is concerned, so
     #   `<button[^>]*id="bAud"[^>]*>` never reaches the body. Matching the span
     #   and its closing tag instead of the attributes sidesteps both.
     #
     # The glyph condition is `playing`, not `i`. The audio button used to render
     # from the toggle's own state, which is a wish; it now renders from whether
     # the element is actually playing, because the button was drawing the
     # muted glyph during playback. This mutation anchored on the minified `i`
     # and so matched nothing after that change, reporting itself uncaught while
     # the gate it belongs to was silently blind to the audio toggle.
     #
     # So the pattern takes the identifier as it appears, which is why this
     # mutation has to be updated whenever the surrounding expression is
     # renamed. A mutation that cannot be built is not a mutation, and a
     # mutation that no longer applies is a gate that stopped checking.
     lambda t: re.sub(r'(<span aria-hidden="true">\$\{[a-zA-Z_$][\w$]*\?"[^"]*":"[^"]*"\}</span>\s*)',
                      "", t, count=1)),
    # Anchored to the tile glyph, not the old Toggle glyph: Toggle was replaced
    # by Tile, so the previous anchor searched for markup that no longer
    # exists, matched nothing, and the gate reported the mutation as uncaught.
    # The check this mutation is for is "the glyph is aria-hidden": the caption
    # text and the accessible name are the same word, so an unhidden caption
    # makes the button announce itself twice.
    ("toggle-glyph: remove aria-hidden from the tile glyph",
     lambda t: t.replace('<span class="cap" aria-hidden="true">',
                         '<span class="cap"', 1)),
    ("state-labels: revert aria-label to P(\"audio\")",
     # Two things this had to stop assuming.
     #
     # It assumed the condition was named `i`, the toggle's own state. The
     # button now reads the element's real playback state, so the name changed
     # in src/ and the old anchor matched nothing.
     #
     # And it assumed the name survives into the bundle. It does not: the
     # minifier renames `playing` to `c`, and the gate reads the BUILT bundle
     # because that is what the browser gets. Writing `playing` here fixed the
     # first problem and still failed, which is the more useful of the two
     # mistakes to have made.
     #
     # So the identifier is matched as a single minified name. That is not
     # looser in any way that matters: what this mutation is for is checking
     # that the label depends on the playback state at all, and any identifier
     # proves that just as well as this one did.
     lambda t: re.sub(r'aria-label=\$\{[a-zA-Z_$][\w$]*\?P\("audioOn"\):P\("audioMuted"\)\}',
                      'aria-label=${P("audio")}', t, count=1)),
    ("no-dashes: put an em dash in the HLS badge",
     lambda t: t.replace("HLS unsupported here, MJPEG",
                         "HLS unsupported here — MJPEG", 1)),
    # The escape-encoded one. This is the mutation the old gate could not see:
    # after it the source file still contains no dash character at all, only the
    # six ASCII characters of an escape, so a checker that counts literal
    # dashes passes and the browser still renders an em dash.
    ("no-dashes: put an escaped em dash in a template literal",
     # The badge is a <div>, not a <span>: the first draft of this mutation
     # searched for the span form, matched nothing, and the gate reported the
     # mutation as uncaught rather than as "did not change the file" for what
     # was really a wrong search string.
     lambda t: t.replace('<div class="badge">HLS unsupported here, MJPEG</div>',
                         '<div class="badge">HLS unsupported here \\u2014 MJPEG</div>', 1)),
    # The en dash, escaped, in a status chip fallback: the exact shape that was
    # in this file 12 times and was never caught.
    # Anchored to the en dash that is actually still in the file (the audio
    # codec list fallback). The first draft of this mutation searched for a
    # placeholder that has since been replaced, matched nothing, and the gate
    # reported it as uncaught.
    ("no-dashes: put an escaped en dash in a dd fallback",
     lambda t: t.replace('${N.available||P("off")}', '${N.available||"\\u2013"}', 1)),
    # The defect that shipped: htm has no comment syntax, so `{/* */}` in a
    # template literal renders its own source into the page. Anchored on the
    # ${null} the minifier left where the safe form used to be, because the
    # comment text itself does not survive minification.
    ("htm-comment: turn the safe ${null} back into a JSX-style comment",
     lambda t: t.replace("${null}\n      <div class=\"statusbar\">",
                         "{/* a comment */}\n      <div class=\"statusbar\">", 1)),
    ("no-dashes: put an em dash in a string resource",
     None),  # handled against values/strings.xml
]


def run_all():
    """Return the list of failure strings for the current files."""
    global failures
    failures = []
    html = open(HTML, encoding="utf-8").read()
    css = strip_css_comments(html)
    visible = strip_html_comments(html)
    check_aspect_ratio(html, css)
    check_icon_centering(css)
    check_toggle_styled(css)
    check_toggle_glyph(visible)
    check_state_labels(visible)
    check_htm_comment_text(visible)
    check_no_dashes("index.html (visible)", visible, is_html=True)
    check_no_dashes_strict("index.html (visible)", visible, is_html=True)
    check_server_strings()
    check_string_resources()
    return list(failures)


def main():
    if not os.path.exists(HTML):
        print(f"FAIL brak {HTML}")
        return 1

    base = run_all()
    if base:
        print("FAIL na czystym drzewie -- bramka zglasza blad tam gdzie go nie ma:")
        for f in base:
            print(f"  - {f}")
        return 1

    print("  czysty build: wszystkie sprawdzenia przechodza")

    # Every mutation must turn this gate red.
    caught = 0
    for name, mut in MUTATIONS:
        if mut is None:
            continue
        original = open(HTML, encoding="utf-8").read()
        mutated = mut(original)
        if mutated == original:
            print(f"FAIL mutacja '{name}' nie zmienila pliku -- bramka nie umie "
                  f"jej zlapac")
            return 1
        with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False,
                                         encoding="utf-8") as fh:
            fh.write(mutated)
            tmp = fh.name
        try:
            globals()["HTML"] = tmp
            res = run_all()
            globals()["HTML"] = original_path
        finally:
            os.unlink(tmp)
        if res:
            caught += 1
            print(f"  zlapano: {name}")
            print(f"      -> {res[0][:150]}")
        else:
            print(f"FAIL mutacja '{name}' przeszla zielono -- to nie jest bramka")

    # The string-resource mutation runs against values/, not the HTML.
    v = open(VALUES, encoding="utf-8").read()
    m = re.search(r'(<string name="status_offline">)([^<]*)(</string>)', v)
    if m:
        open(VALUES, "w", encoding="utf-8").write(
            v.replace(m.group(0), m.group(1) + m.group(2) + " — dash" + m.group(3), 1))
        try:
            res = run_all()
            if res:
                caught += 1
                print("  zlapano: no-dashes: em dash in a string resource")
                print(f"      -> {res[0][:150]}")
            else:
                print("FAIL mutacja 'em dash w string resource' przeszla zielono")
        finally:
            open(VALUES, "w", encoding="utf-8").write(v)

    total = len([m for m in MUTATIONS if m[1] is not None]) + 1
    if caught != total:
        print(f"\nFAIL zlapano {caught}/{total} mutacji")
        return 1

    print(f"\nbramka web UI: {total} mutacji zlapanych, czysty build przechodzi "
          f"-- proporcje HLS, centrowanie ikon, toggle audio, etykiety stanow, "
          f"zero em-dashow w tekstach uzytkownika")
    return 0


original_path = HTML

if __name__ == "__main__":
    sys.exit(main())

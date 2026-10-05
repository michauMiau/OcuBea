#!/usr/bin/env python3
"""Gate: the audio codec menu must not offer a path a browser cannot take.

Why this exists
---------------
The Web UI built its codec picker from whatever `/status.json` listed, so the
device decided what the browser was offered. Android can encode codecs no
browser will play, and until now nothing in the app said which of the two
kinds it was serving. This asserts the measured contract instead:

  1. every menu entry carries the measured capability flags
     (`browser_playable`, `mse_capable`) and its real served Content-Type,
  2. the Content-Type comes from AudioCodecProbe.Option.contentTypeForHttp and
     not from a second hardcoded string, so the served header and the
     advertised header cannot drift,
  3. the formats a browser genuinely cannot decode (`audio/mp4a-latm` is the
     MediaCodec mime, never a served type; raw FLAC is not MSE-capable) are not
     advertised as anything a client should point an <audio> element at,
  4. `wav` is present on every device, because it needs no encoder.

Evidence behind the flags (Chromium 148, bytes captured from the phone, served
paced and chunked with the app's own Content-Type, playback asserted by an
element's currentTime advancing):

  audio/wav        canPlayType=maybe    MSE=false   plays (+3.01 s)
  audio/aac        canPlayType=probably MSE=true    plays (+3.01 s)
  audio/flac       canPlayType=probably MSE=false   plays (+3.06 s)

So no current entry is unplayable, and this gate's job is to keep that true and
to keep the flags honest if a future container is added.

Run:  python3 tools/verify_audio_browser_capability.py
Exit: 0 green, 1 red, with the failing rule named.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "app/src/main/java/com/ocubea/server/AudioCodecProbe.kt"
TELEMETRY = ROOT / "app/src/main/java/com/ocubea/server/TelemetryHandler.kt"
STREAMSERVER = ROOT / "app/src/main/java/com/ocubea/server/StreamServer.kt"

failures: list[str] = []


def fail(rule: str, msg: str) -> None:
    failures.append(f"{rule}: {msg}")


def check_flags_declared(probe: str) -> None:
    for field in ("browserPlayable", "mseCapable"):
        if f"val {field}: Boolean" not in probe:
            fail("flags-declared",
                 f"Option has no {field} field, so /status.json cannot tell the "
                 f"picker what a browser can play")


def check_flags_reported(telemetry: str) -> None:
    """The flags only matter if they reach the client."""
    for json_key, field in (("browser_playable", "browserPlayable"),
                            ("mse_capable", "mseCapable"),
                            ("content_type", "contentTypeForHttp")):
        if f'\\"{json_key}\\"' not in telemetry:
            fail("flags-reported",
                 f"/status.json does not emit {json_key}, so the Web UI cannot "
                 f"use the measured capability of {field}")
        elif field not in telemetry:
            fail("flags-reported",
                 f"/status.json hardcodes {json_key} instead of reading "
                 f"{field}, so the two can disagree")


def check_content_type_single_source(streamserver: str) -> None:
    """A second literal 'audio/x-wav' in the handler is a second truth."""
    literals = re.findall(r'"(audio/[a-z0-9.+=\-; ]*)"', streamserver)
    bad = [x for x in literals if x not in ("audio/wav",)]
    if bad:
        fail("content-type-single-source",
             f"StreamServer hardcodes a served audio Content-Type {bad!r}; the "
             f"only source of truth is Option.contentTypeForHttp")
    if "contentTypeForHttp" not in streamserver:
        fail("content-type-single-source",
             "the WAV endpoint does not take its Content-Type from the menu "
             "option, so the served and advertised types can drift")


def check_mediacodec_mime_not_served(probe: str) -> None:
    """`audio/mp4a-latm` is a MediaCodec name, not a container a browser takes."""
    body = probe.split("contentTypeForHttp")[1].split("}")[0] if "contentTypeForHttp" in probe else ""
    if "audio/mp4a-latm" in body:
        fail("no-mediacodec-mime-on-the-wire",
             "contentTypeForHttp can return audio/mp4a-latm; browsers report "
             "canPlayType('') for it, so a client would refuse a valid stream")


def check_flac_not_mse(probe: str) -> None:
    """FLAC has no MSE source buffer in any browser engine that matters."""
    m = re.search(r"val mseCapable: Boolean = when \(id\) \{(.*?)\n        \}", probe, re.S)
    if not m:
        return
    arms = m.group(1)
    for cid in ("wav", "flac", "amrnb"):
        if re.search(rf'"{cid}"\s*->\s*true', arms):
            fail("mse-capability",
                 f"{cid} is marked mse_capable, but Chromium reports "
                 f"MediaSource.isTypeSupported false for its container")


def check_wav_always_offered(probe: str) -> None:
    """The compatibility floor must not depend on what the device can encode."""
    if "const val canWav = true" not in probe:
        fail("wav-always-offered",
             "AudioCodecProbe no longer states canWav, so the Web UI's codec "
             "picker drops WAV on devices whose probe found no encoder")
    if 'wav=$canWav' not in probe:
        fail("wav-always-offered",
             "summary() does not include wav=..., and the picker builds its "
             "whitelist from that string, so WAV disappears from the menu")


def main() -> int:
    for path in (PROBE, TELEMETRY, STREAMSERVER):
        if not path.exists():
            print(f"MISSING {path}", file=sys.stderr)
            return 2

    probe = PROBE.read_text(encoding="utf-8")
    telemetry = TELEMETRY.read_text(encoding="utf-8")
    streamserver = STREAMSERVER.read_text(encoding="utf-8")

    check_flags_declared(probe)
    check_flags_reported(telemetry)
    check_content_type_single_source(streamserver)
    check_mediacodec_mime_not_served(probe)
    check_flac_not_mse(probe)
    check_wav_always_offered(probe)

    if failures:
        print(f"FAIL  {len(failures)} rule(s) broken:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("PASS  audio browser-capability contract holds")
    print("      menu entries carry browser_playable / mse_capable / content_type")
    print("      served Content-Type has a single source of truth")
    print("      wav is offered unconditionally")
    return 0


if __name__ == "__main__":
    sys.exit(main())
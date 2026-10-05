#!/usr/bin/env python3
"""Mutation test for verify_audio_browser_capability.py.

A gate that has never been seen to go red is not evidence of anything. This
applies each defect the gate targets to a COPY of the real files, confirms the
copy actually changed (a mutation that fails to alter the file is a bug in the
mutation, not a pass), then runs the gate's own check functions against the
mutated text and requires a failure.

Nothing here touches the repository: every mutation is applied to an in-memory
copy and the gate is imported and called directly.

Run: python3 tools/verify_audio_browser_capability_mutation.py
"""
from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GATE = ROOT / "tools/verify_audio_browser_capability.py"

spec = importlib.util.spec_from_file_location("vabc", GATE)
vabc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vabc)


def read(p: str) -> str:
    return (ROOT / p).read_text(encoding="utf-8")


PROBE_P = "app/src/main/java/com/ocubea/server/AudioCodecProbe.kt"
TELE_P = "app/src/main/java/com/ocubea/server/TelemetryHandler.kt"
SRV_P = "app/src/main/java/com/ocubea/server/StreamServer.kt"

BASE = {
    PROBE_P: read(PROBE_P),
    TELE_P: read(TELE_P),
    SRV_P: read(SRV_P),
}


def run_gate(files: dict[str, str]) -> list[str]:
    vabc.failures.clear()
    vabc.check_flags_declared(files[PROBE_P])
    vabc.check_flags_reported(files[TELE_P])
    vabc.check_content_type_single_source(files[SRV_P])
    vabc.check_mediacodec_mime_not_served(files[PROBE_P])
    vabc.check_flac_not_mse(files[PROBE_P])
    vabc.check_wav_always_offered(files[PROBE_P])
    return list(vabc.failures)


def mutate(name: str, target: str, old: str, new: str, rule: str) -> bool:
    """Apply a mutation; require it to change the file AND to trip `rule`."""
    files = dict(BASE)
    if old not in files[target]:
        print(f"  SKIP  {name}: anchor not found in {Path(target).name} -- "
              f"the gate's premise moved, fix the mutation")
        return False
    files[target] = files[target].replace(old, new, 1)
    if files[target] == BASE[target]:
        print(f"  FAIL  {name}: mutation did not change the file")
        return False
    got = run_gate(files)
    tripped = any(f.startswith(rule + ":") for f in got)
    print(f"  {'PASS ' if tripped else 'FAIL '} {name}")
    print(f"        rule={rule}  failures={got}")
    if not tripped:
        print(f"        -> gate did not go red on this defect; it is not testing it")
    return tripped


def main() -> int:
    print("mutation test for verify_audio_browser_capability.py")
    print(f"baseline failures: {run_gate(dict(BASE))}  (must be empty)")
    print()

    results = [
        # 1. The capability flags disappear: the picker is back to guessing.
        mutate("drop browserPlayable from Option",
               PROBE_P, "val browserPlayable: Boolean", "val browserPlayableX: Boolean",
               "flags-declared"),
        # 2. The flags stop reaching the client, so the UI cannot use them.
        mutate("stop emitting browser_playable in /status.json",
               TELE_P, r'\"browser_playable\":${o.browserPlayable},', "",
               "flags-reported"),
        # 3. /status.json lies: hardcodes the flag instead of reading the probe.
        mutate("/status.json hardcodes mse_capable=true",
               TELE_P, r'\"mse_capable\":${o.mseCapable}',
               r'\"mse_capable\":true',
               "flags-reported"),
        # 4. The second source of truth comes back: a hardcoded audio/x-wav.
        mutate("StreamServer hardcodes audio/x-wav again",
               SRV_P, "AudioCodecProbe.options().first { it.id == \"wav\" }.contentTypeForHttp",
               '"audio/x-wav"',
               "content-type-single-source"),
        # 5. The MediaCodec mime goes back on the wire.
        mutate("contentTypeForHttp returns audio/mp4a-latm",
               PROBE_P, '"aac" -> "audio/aac"', '"aac" -> "audio/mp4a-latm"',
               "no-mediacodec-mime-on-the-wire"),
        # 6. FLAC is claimed MSE-capable, which it is not.
        mutate("flac marked mse_capable",
               PROBE_P, '"aac" -> true // audio/aac and audio/mp4; codecs="mp4a.40.2"',
               '"aac" -> true\n            "flac" -> true',
               "mse-capability"),
        # 7. WAV is dropped from the menu whitelist again -- the original bug.
        mutate("summary() drops wav= again",
               PROBE_P, '"wav=$canWav default=', '"default=',
               "wav-always-offered"),
        mutate("canWav constant removed",
               PROBE_P, "const val canWav = true", "const val canWav = false",
               "wav-always-offered"),
    ]

    print()
    if all(results):
        print(f"PASS  {len(results)}/{len(results)} mutations each turned the gate red")
        return 0
    bad = len(results) - sum(1 for r in results if r)
    print(f"FAIL  {bad} mutation(s) did not prove the gate can go red")
    return 1


if __name__ == "__main__":
    sys.exit(main())
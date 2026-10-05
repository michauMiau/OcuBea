#!/usr/bin/env python3
"""Verify on the real phone that the ladder holds the rung the user asked for.

The defect this checks: a rebind leaves the F3311 camera dead for ~2.6 s,
`settleMs` was 3 s, and the first window the governor judged covered only the
~400 ms tail of that. It read 7 fps against a request of 15 and stepped
1280x720 -> 960x540, on a phone that then sustained 15 fps at 720p.

So the thing worth asserting is not "the fps is high" -- it is that the *user's
rung survives*. And it has to be asserted over several seconds, because a single
sample right after a rebind is exactly the sample that used to lie.

Two sources, because one of them lies:
  - the app log, which carries `adaptive: DOWN ...` and `anchored at ...`
  - /status.json, which BLOCKS while a rebind is in flight and therefore returns a
    stale snapshot to any poller that crosses one

The log is the authority. /status.json is reported but never used to conclude that
a transition happened.

Usage: python3 tools/verify_rung_holds.py [seconds]
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

# The Sony F3311 (Android 6) is wired to the DietPi box, not to this machine,
# so a bare `adb` here finds nothing. It has to go over SSH -- and it must be
# addressed by -s, because both phones sit on that same adb server and an
# unqualified `adb shell` fails with "more than one device/emulator".
#
# Both serials, so the gate can be pointed at either phone:
#   ea79444a  Redmi Note 12 Pro, 22101320G, Android 16 / API 36, 192.168.1.29
#   RQ3002EA5J Sony F3311, Android 6 / API 23, 192.168.1.184
#
# BASE follows the serial, because the two phones answer on different IPs and
# hardcoding one made this gate silently measure nothing but A6.
DEVICES = {
    "ea79444a": "http://192.168.1.29:8080",
    "RQ3002EA5J": "http://192.168.1.184:8080",
}
ADB_SERIAL = sys.argv[2] if len(sys.argv) > 2 else "RQ3002EA5J"
BASE = DEVICES.get(ADB_SERIAL, "http://127.0.0.1:8080")
DIETPI = ["sshpass", "-p", "REDACTED", "ssh",
          "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=8",
          "dietpi@192.168.1.199"]
PKG = "com.ocubea"
ACTIVITY = f"{PKG}/.MainActivity"
USER_RUNG = "1280x720"
TARGET_FPS = 15
DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 90


def adb(*args: str, timeout: int = 20) -> str:
    cmd = DIETPI + [f"adb -s {ADB_SERIAL} " + " ".join(args)]
    return subprocess.run(cmd, capture_output=True, text=True,
                          timeout=timeout).stdout


def status(retries: int = 4) -> dict | None:
    """Read /status.json, retrying: it blocks during a rebind and then serves a
    stale snapshot, so a single read is not evidence of anything.

    It also checks that OcuBea is the thing answering, because on the Sony
    F3311 another camera app (Server: IP Webcam Server 0.4, com.pas.webcam) holds
    port 8080 and answers /status.json with its own five keys. A gate reading
    that would report `?` for every field and call it a failed ladder --
    indistinguishable from a real regression. A missing key here is a port
    conflict to report, not a measurement.
    """
    for _ in range(retries):
        try:
            with urllib.request.urlopen(f"{BASE}/status.json", timeout=8) as response:
                body = json.loads(response.read())
            if "pipeline" not in body or "hls" not in body:
                return {"__foreign__": sorted(body.keys())}
            return body
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
            time.sleep(1.5)
    return None


def ts() -> str:
    return datetime.now(timezone.utc).strftime("%H:%M:%S")


def main() -> int:
    print(f"=== rung-hold verification, user_rung={USER_RUNG} target_fps={TARGET_FPS} ===")
    print(f"phone {ADB_SERIAL} at {BASE}, observing for {DURATION}s\n")

    # Pin the state the user asked for, through the endpoint that actually works.
    # /settings/resolution returns Ok and changes nothing -- that cost a whole
    # earlier sweep its conclusion.
    print(ts(), "setting video_size via /settings/video_size ...")
    with urllib.request.urlopen(
        f"{BASE}/settings/video_size?set={USER_RUNG}", timeout=15
    ) as response:
        print(" ", response.read().decode().strip())

    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", ACTIVITY)

    downs: list[str] = []
    anchored: list[str] = []
    samples: list[dict] = []
    end = time.time() + DURATION

    while time.time() < end:
        log = adb("logcat", "-d", "-s", "OcuBeaCam:V")
        for line in log.splitlines():
            if "adaptive: DOWN" in line:
                downs.append(line.strip())
            if "anchored at" in line:
                anchored.append(line.strip())

        state = status()
        if state and "__foreign__" in state:
            print(f"  t={round(DURATION - (end - time.time())):3}s port {BASE} odpowiada "
                  f"NIE OcuBea: klucze={state['__foreign__']}")
            print("        konflikt portu 8080 z inną aplikacją kamery -- "
                  "bramka nie może tu nic zmierzyć")
            return 3
        if state:
            pipe = state.get("pipeline", {})
            rung = pipe.get("rung") or state.get("resolution_effective") or "?"
            samples.append({
                "t": round(DURATION - (end - time.time())),
                "rung": rung,
                "arrived": pipe.get("arrived_fps"),
                "measured": pipe.get("measured_fps"),
                "entered": pipe.get("frames_entered"),
                "ratio": pipe.get("governor_ratio"),
                "user_rung": pipe.get("user_rung"),
            })
            s = samples[-1]
            print(
                f"  t={s['t']:3}s rung={s['rung']} (asked {s['user_rung']}) "
                f"arrived={s['arrived']} measured={s['measured']} ratio={s['ratio']}"
            )
        else:
            print(f"  t={round(DURATION - (end - time.time())):3}s /status.json blocked "
                  f"(rebind in flight -- expected, and why it is not trusted)")

        time.sleep(4)

    print(f"\n=== log: anchor ({len(anchored)} lines) ===")
    for line in anchored[-4:]:
        print(" ", line)
    print(f"=== log: DOWNGRADES ({len(downs)}) ===")
    for line in downs[-8:]:
        print(" ", line)

    final = samples[-1] if samples else {}
    held = str(final.get("rung")) == USER_RUNG

    print("\n=== verdict ===")
    if not samples:
        print("FAIL: no /status.json sample at all -- the phone is not serving")
        return 2
    print(f"final rung            : {final.get('rung')} (asked {USER_RUNG})")
    print(f"arrived / measured     : {final.get('arrived')} / {final.get('measured')}")
    print(f"anchor lines in log    : {len(anchored)}")
    print(f"downgrades in log      : {len(downs)}")

    if not held:
        print("\nFAIL: the ladder did not hold the rung the user asked for")
        for line in downs[-4:]:
            print("  ", line)
        return 1
    if downs:
        print("\nFAIL: the rung survived but the ladder still fired a downgrade")
        for line in downs[-4:]:
            print("  ", line)
        return 1
    print("\nPASS: the user rung held for the whole window and no downgrade was "
          "logged -- the ramp guard did its job.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
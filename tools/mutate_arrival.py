"""Mutation gate for FrameArrivalAccount wiring.

Each mutation removes exactly one increment or changes one formula in
CameraManager.kt. A mutation that leaves the suite GREEN means the test does
not actually pin that piece of the accounting — the failure mode this repo has
hit repeatedly (a "test" that only proves the source looks the way it does).

Runs strictly sequentially and never in parallel with an edit of the file under
test: it rewrites CameraManager.kt in place, so a concurrent patch would be
overwritten. Restore happens in a `finally`, and the restored file is verified
by SHA-256, so an interrupted run cannot leave a mutated APK behind.

Usage: python3 tools/mutate_arrival.py
"""
import hashlib
import os
import subprocess
import sys
import glob
import xml.etree.ElementTree as ET

os.chdir('/root/OcuBea')
P = 'app/src/main/java/com/ocubea/camera/CameraManager.kt'
env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')


def run():
    subprocess.run(['./gradlew', ':app:testDebugUnitTest', '--console=plain', '-q',
                    '--rerun-tasks'], env=env, capture_output=True, timeout=1800)
    tot = err = 0
    names = []
    for y in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
        x = ET.parse(y).getroot()
        tot += int(x.get('tests', 0))
        for tc in x.iter('testcase'):
            if tc.find('failure') is not None or tc.find('error') is not None:
                err += 1
                names.append(tc.get('classname', '').split('.')[-1] + '.' + tc.get('name', ''))
    return tot, err, sorted(set(names))


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()[:12]


ORIG = open(P).read()
ORIG_SHA = sha(ORIG)

# Each entry: (label, [(find, replace), ...]). Every `find` must occur exactly
# once, so a mutation cannot silently become a no-op.
MUTS = [
    ("arrival.observe zamienione na no-op (nic nie liczy)",
     [("when (arrival.observe(isStreaming, now, lastFrameNanos, targetFps))",
       "when (if (true) FrameArrivalAccount.Outcome.ACCEPTED else arrival.observe(isStreaming, now, lastFrameNanos, targetFps))")]),

    # The fps limiter's own `dropDecisions++` is no longer part of the arrival
    # accounting -- FrameArrivalAccount does the counting now -- so mutating it
    # proves nothing. This variant instead un-winds the limiter's counted
    # return, which is the thing that actually moves returnedEarly.
    ("skipped klatka nie wraca (brak return po odmowie limitera)",
     [("                FrameArrivalAccount.Outcome.LIMITED_BY_FPS -> {\n                    dropDecisions++\n                    imageProxy.close()\n                    return\n                }",
       "                FrameArrivalAccount.Outcome.LIMITED_BY_FPS -> {\n                    dropDecisions++\n                    imageProxy.close()\n                }")]),

    ("brak recordPublished() (klatki giną po-limitowo)",
     [("                            arrival.recordPublished()\n", "")]),

    ("recordSkippedNoConsumer zamienione na recordSaturated",
     [("                arrival.recordSkippedNoConsumer()",
       "                arrival.recordSaturated()")]),

    ("brak recordSaturated() (zdarzenia nasycenia niewidoczne)",
     [("                arrival.recordSaturated()\n", "")]),

    ("frames_lost liczone względem returnedEarly zamiast published",
     [('"frames_lost" to (framesEntered - frameCounter),',
       '"frames_lost" to (framesEntered - framesReturnedEarly),')]),

    ("frames_unaccounted hardkodowane na 0 ( luka znika z telemetrii)",
     [('"frames_unaccounted" to arrival.unaccounted(),',
       '"frames_unaccounted" to 0L,')]),

    ("pole frames_entered usunięte z telemetrii",
     [('        "frames_entered" to framesEntered,\n', '')]),

    ("observe dostaje isStreaming=true (klatki w wind-down nie widziane)",
     [("when (arrival.observe(isStreaming, now, lastFrameNanos, targetFps))",
       "when (arrival.observe(true, now, lastFrameNanos, targetFps))")]),
]

try:
    print(f"baseline: {sha(ORIG)}  ({ORIG.count('arrival.')} call sites)\n", flush=True)
    for name, edits in MUTS:
        src = ORIG
        for a, b in edits:
            n = src.count(a)
            if n != 1:
                print(f"  POMINIETO '{name}': wzorzec wystepuje {n}x (musi byc 1)")
                src = None
                break
            src = src.replace(a, b, 1)
        if src is None:
            continue
        open(P, 'w').write(src)
        tot, err, names = run()
        flag = "czerwone" if err > 0 else "*** ZIELONE - TEST BEZ SILY ***"
        print(f"{name}\n    {tot} testow, {err} czerwonych  {flag}", flush=True)
        for nm in names[:6]:
            print(f"      {nm}", flush=True)
finally:
    open(P, 'w').write(ORIG)
    back = sha(open(P).read())
    print(f"\nPRZYWRÓCONE {back} (oczekiwano {ORIG_SHA}): "
          + ("OK" if back == ORIG_SHA else "*** NIEZGODNE ***"), flush=True)
    if back != ORIG_SHA:
        sys.exit(1)
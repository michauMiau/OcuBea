# Performance pass — Sony F3311, measured 2026-10-02

Every number came from the phone. Where a conclusion needed the HAL, logcat
settled it. **The first version of this document reported a bug that does not
exist; the refutation is recorded in full at the end, and it is the more
useful half.**

## Verdict

The app is **not CPU-bound**, and the frame rate is **not** limited by the work
the app does.

Per-thread CPU, `top -t`, idle and with three MJPEG viewers:

| | sum of threads | dominant thread |
|---|---|---|
| idle, 0 viewers | 28% | `CameraDeviceGLT` 27.1% |
| 3 MJPEG viewers | 26% | `CameraDeviceGLT` 25.5% |

`ocubea-analysis`, `ocubea-http`, `ocubea-encode-*`, `ocubea-audio` all read
**0.0%**. The only hot thread is the camera HAL's GL thread — the sensor read
path. The encode pool added in an earlier pass is not carrying this device, and
motion analysis, which was 91% of a thread in an earlier pass, is now invisible.

Delivered rate, one viewer, 20 s, counted from the published-frame counter:

```
frames  1177 -> 1398   = 11.1/s      <- the real delivered rate
```

against a HAL offering **24 fps** (`ImageReader-864x480f23m4`,
`queueBuffer: fps=24.13`). So roughly half the sensor's frames are not
reaching the encoder, while the process sits at 26% of 8 cores with every
application thread at zero.

**The bottleneck is between the HAL and the analyzer, and the app's own
telemetry cannot show you where.** See "What the telemetry cannot see".

## Finding 1 — `fps` and `fps_requested` do not report the frame rate

Three fields in one `/status.json` response, three different meanings:

| field | what it actually is | source |
|---|---|---|
| `frames` | **published** frames, cumulative — the only honest counter | `frameCounter`, at `CameraManager.kt:1048`, *before* `publish` |
| `fps` | `frameHub.fps`, a 1-second rolling count inside `publish()` | `FrameHub.kt:68-72` |
| `fps_requested` | **also** `frameHub.fps` — the same delivered value | `TelemetryHandler.kt:145` reads `cfg["fps"]` |

Measured with `target_fps=30`: `fps_requested=16`. The field says *requested*
and reports delivery. Any sweep that trusts it is reading the wrong number.

`fps` also stalls: four consecutive viewer sessions reported `fps=11` while
`frames` moved 899 → 953. `FrameHub.fps` only advances when a frame passes
`publish()`, so it cannot distinguish "slow" from "stopped", and it went stale
across sessions rather than tracking them.

## Finding 2 — the adaptive governor is holding a rung it has no reason to

`pipeline.rung` sits at `480x270` while `user_rung` is `1280x720`, and the
delivered frame is 864x480. Meanwhile:

```
fps_relief_steps=0  sticky=False  oscillations=0  measured_fps=10  fps_effective=0
```

The governor sees ~10 fps against a requested 15 and steps the resolution down
— while the CPU it would be relieving sits at 26% with every app thread at
0.0%. **There was no pressure to step down from.** Headroom is an input to the
governor's decision, not a justification for it.

`fps_effective=0` is the tell: the *relief* path the governor uses to answer a
shortfall has never fired, so the shortfall is being answered with resolution
instead. That is the mechanism to look at first.

## Finding 3 — `dropped` is the fps limiter, named as if it were a loss

`dropDecisions++` at `CameraManager.kt:917` fires inside the fps limiter, before
any consumer work. It moves with `target_fps` and with nothing else. Measured
over 10 s with a viewer attached:

| `target_fps` | `dropped`/s | expected if limiter, HAL=24 |
|---|---|---|
| 15 | 3.5 | 9 |
| 10 | 0.0 | 14 |
| 24 | 0.0 | 0 |

Only the middle case is even close, and it is well under. Read as `dropped`,
this field sends you looking for a saturation problem that is not there — which
is exactly where the first version of this pass went.

## What the telemetry cannot see

The gap between 24 fps at the HAL and 11 fps published is real, and **no field
in `/status.json` locates it**. `frames` counts the frames that made it out;
`dropped` counts the limiter's skips; `null_bitmaps` counts frames where
`jpegNeeded` was false; `pipeline_errors` is 0. Nothing counts frames that
arrived at `analyzeFrame` and were lost between the FPS check and the encoder.

That gap is where the missing ~13 fps/s are, and it is the thing to instrument
next: a counter incremented at entry to `analyzeFrame` and again at
`frameCounter++`, with the difference as an explicit `frames_lost` field. The
existing per-stage `Metrics.timer(FRAME_ANALYZE)` spans the whole method, so it
cannot separate "never arrived" from "arrived and died".

## Also seen, not diagnosed

- `No frames for 30s — restarting camera` fires repeatedly, and during those
  windows **every** counter freezes — `null_bitmaps`, `dropped` and `frames`
  all stop dead while `fps` keeps reporting 11-17. The logcat at that moment is
  hundreds of `ImageReader ... dequeueBuffer() in a disconnected state` per
  second from the HAL. The counters freezing is the observable fact; the cause
  is not established.
- `GraphicBufferMapper::unlock: (overtime > 1 ms)` from `MtkCam` — HAL-side,
  consistent with `CameraDeviceGLT` being the hot thread.

## Reproducing

```bash
# per-thread CPU. This toybox has no -H and no -b; -t is the flag, and CPU%
# is column 4, not the first one top prints.
adb shell top -t -n 2 -d 4 -s cpu | grep com.ocubea

# what the HAL really delivers
adb logcat -d | grep 'ImageReader.*queueBuffer'

# the only honest rate: two status reads 20 s apart
curl -s http://192.168.1.184:8080/status.json   # frames
```

`fps` is not a measurement. Use `frames` sampled twice.

## Refutation — the first diagnosis, and why it was wrong

The first version of this document claimed `toBitmap()` was returning null on
half the frames and that this was the ceiling. The evidence looked strong:

```
null_bitmaps  +120 over 10 s (12/s)
frames        +0  over 10 s
pipeline_errors 0
```

It was **wrong**, and the error is worth more than the finding was.

**The counter is misnamed.** `CameraManager.kt:1009-1015`:

```kotlin
val bitmap = if (jpegNeeded) {
    val raw = imageProxy.toBitmap()
    ...
} else null                 // <- null by construction
imageProxy.close()
// A null here after an intentional skip is expected, not a failure.
if (bitmap == null) { ...; nullBitmaps++; return }
```

When no viewer is attached and motion, HLS and recording are off, `jpegNeeded`
is false, the bitmap is null **by design**, and that is counted as
`null_bitmaps`. The code says so in a comment directly above the counter. It is
a skip counter wearing the name of a failure.

**The decisive test.** With a viewer attached for 20 s:

```
A  no viewer:   viewers=0  frames+  0  null+126  dropped+24   /10s
B  one viewer:  viewers=1  frames+116  null+  0  dropped+34   /10s
```

Frames published, zero nulls. `toBitmap()` works. Under a viewer the counter
does not merely stay low, it goes to **exactly zero**, because the skip path is
no longer taken. A genuinely failing `toBitmap()` would not care how many
people were watching.

**Where the reasoning failed.** The evidence was consistent with two
hypotheses — a null return, or a skip — and I picked one without a test that
separates them. The tell was in plain sight and I read past it: `frames` frozen
at *exactly* 0 is not what a partial failure looks like, because a real
`toBitmap()` failure would still let some frames through. And the earlier
observation that the app "publishes nothing" was itself the correct behaviour:
with nobody connected there is nothing to publish.

The general lesson, now in the skill: **a counter named after a failure will
count the non-failure case too, and its rate will scale with whatever drives
the skip.** `null_bitmaps` grew at 12/s against a 24 fps HAL — a ratio that
looked like "half the frames fail" and was in fact "every frame is skipped,
because no one wants one". Check what the counter increments *on*, at the line,
before reading its rate as a failure rate.

## Where the first pass also mis-stated the sweep

The flat sweep (`?set=5|15|24|30`, identical results on every arm) was read as
"the setting landed but the frames died downstream, therefore the defect is
downstream". The correct reading is narrower: **a flat sweep of `fps` proves
nothing here, because `fps` is not the measurement.** Once `frames` is used
instead, the sweep does move — one viewer at target 15 delivered 11.1/s, and
`dropped` tracked the limiter. The sweep was flat because the instrument was
broken, not because the system was.

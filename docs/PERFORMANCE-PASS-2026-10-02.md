# Performance pass — Sony F3311, measured 2026-10-02

Every number below came from the phone, not from reading the code. Where a
conclusion needed the HAL, logcat settled it. Commands are reproducible.

## Verdict first

The app is **not CPU-bound** and the frame-rate ceiling is **not** the one this
repo has been chasing for several passes. Measured, per thread, with nothing
attached and with three MJPEG viewers attached:

| | sum of threads | dominant thread |
|---|---|---|
| idle, 0 viewers | 28% | `CameraDeviceGLT` 27.1% |
| 3 MJPEG viewers | 26% | `CameraDeviceGLT` 25.5% |

`ocubea-analysis`, `ocubea-http`, `ocubea-encode-*` and `ocubea-audio` all read
**0.0%**. The only hot thread is the camera HAL's GL thread, which is the
sensor read path. The encode pool that a previous pass added is not carrying
this device, and neither is motion analysis — the thing that was 91% of a thread
in an earlier pass is now invisible.

**So the pipeline has headroom, and the adaptive governor is spending it.**
`pipeline.rung` sits at `480x270` while `user_rung` is `1280x720`, and the
delivered frame is 864x480. The governor stepped the resolution down because it
believes fps is short — but fps is short because the governor stepped it down,
or because of the defect below, and 26% CPU is not evidence of pressure.

## What the phone actually delivers

HAL, from `ImageReader-864x480f23m4` in logcat, immediately after a rebind:

```
queueBuffer: fps=24.13 ... fps=23.99 ... fps=23.99 ... fps=21.69
```

**24 fps from the sensor.** The app's own numbers, 10 s apart, same session:

| counter | delta over 10 s | rate |
|---|---|---|
| `null_bitmaps` | +120 | 12/s |
| `dropped` | +30 | 3/s |
| `frames` (published) | **+0** | **0/s** |
| `overlay.frames` | +0 | 0/s |

## Three findings, in the order they should be fixed

### 1. `toBitmap()` returns null on roughly half the frames, and the frame is discarded

`null_bitmaps` grows at 12/s against a 24 fps HAL — one null per two frames,
consistently, with `pipeline_errors: 0`, `null_bitmaps` never counted as an
exception, and no throwable anywhere in logcat. `CameraManager.kt:1010` does
`imageProxy.toBitmap()`, then line 1015 discards the frame on null.

`frames` — the counter of *published* frames — sits frozen at 458 for the whole
session. Not slow: **zero**. The camera is running and every frame it produces
is thrown away.

This is the ceiling. Everything else is downstream of it.

The skill's rule applies directly: *a zero drop counter is only evidence once
you have counted exceptions*. Here the opposite holds — `null_bitmaps` is
non-zero and is being treated as a curiosity. It is the bug.

### 2. `frames` never moves, which means the app cannot report its own frame rate

`frameCounter` is incremented at `CameraManager.kt:1048`, inside the encoder
task, *after* `toBitmap()` succeeds. So with every frame lost to a null
bitmap, the published counter is pinned. `fps` in `/status.json` reads 12-15
because it comes from `frameHub.fps`, a different counter that counts something
else entirely — and `fps_requested` is wired to the same field
(`TelemetryHandler.kt:145` reads `cfg["fps"]`, which is `frameHub.fps`), so
`fps_requested` reports the delivered rate and not the requested one. Measured:
`target_fps=30` with `fps_requested=16`. A field named `fps_requested` that
reports the delivered rate will make every future sweep lie, including this one.

### 3. The `dropped` counter is the fps limiter, not a loss

`dropDecisions++` at `CameraManager.kt:917` fires inside the fps limiter, before
any consumer work. Reported as `"dropped"`, it reads as frames lost to overload.
It is the app choosing to skip. Measured: target 15 fps against a 24 fps HAL
means the limiter must reject ~9 frames/s and indeed rejects 3/s at target 30 —
the counter moves with the setting and nothing else. Naming it `dropped` is
what sent this pass looking for a saturation problem that is not there.

## The sweep that looked like proof and was not

`/settings/fps?set=5|15|24|30` produced **identical** delivered fps and an
unchanged `null_bitmaps` on every arm:

```
 set  delivered  req  rung      src
   5        14   16  480x270   864x480
  15        14   16  480x270   864x480
  24        14   16  480x270   864x480
  30        14   16  480x270   864x480
```

A flat sweep means the limiter is not the constraint — which is right, and it
was a useful step. What it did **not** mean is that the setting is broken: the
setting *did* land (`target_fps` tracked 30 and held), and it could not change
the outcome because the frames were being discarded downstream of it. This is
the skill's flat-sweep trap in a new place: the sweep moved the right knob and
still proved nothing, because the defect sits behind the knob.

## Also seen, not yet diagnosed

- `No frames for 30s — restarting camera` fires repeatedly. Consistent with
  finding 1: the watchdog's heartbeat is upstream of the discard, so this needs
  its own look rather than being assumed to be a consequence.
- `GraphicBufferMapper::unlock: (overtime > 1 ms)` from `MtkCam` — HAL-side,
  consistent with `CameraDeviceGLT` being the hot thread.

## Reproducing

```bash
# per-thread CPU; note -t and the column layout on Android 5.1 toybox top
adb shell top -t -n 2 -d 4 -s cpu    # PID TID PR CPU% S VSS RSS PCY UID Thread Proc
adb shell top -t -n 1 -s cpu | grep com.ocubea

# what the HAL really delivers
adb logcat -d | grep 'ImageReader.*queueBuffer'

# the counters that disagree
curl -s http://192.168.1.184:8080/status.json | python3 -m json.tool | grep -A14 pipeline
```

`top -H` and `top -b` do not exist on this device's toybox; `-t` is the flag,
and CPU% is column 4, not the one `top` prints first. A first attempt parsed
the UID column and reported every thread at 3500%.

## Skill corrections this pass produced

- `references/frame-rate-diagnosis.md` says the fix is "fewer pixels and/or
  parallel encoding" on a CPU-bound device. On this phone 26% of 8 cores is not
  bound, and the largest single cost is a null return that costs no CPU at all.
  Add: **check `null_bitmaps` before any CPU conclusion** — a per-frame
  `toBitmap()` returning null is invisible to every CPU measurement, and it
  presents as a frame-rate ceiling with an empty cause.
- The ladder step 1 ("if delivered fps is identical at every target, the ceiling
  is upstream of your code") gave the wrong answer here. It should be qualified:
  a flat sweep means the *limiter* is not the constraint. If `null_bitmaps` is
  growing, the ceiling is your own discard path and no fps setting will ever
  move the number.

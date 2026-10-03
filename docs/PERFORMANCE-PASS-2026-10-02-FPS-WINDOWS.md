# Performance pass — 2026-10-02, second pass

Continues `PERFORMANCE-PASS-2026-10-02.md`, which established that ~17 fps is the
F3311's ceiling and that no frame is lost in the analyzer. That pass found one
wrong number in `CameraManager`: a single FPS window whose call site sat *after*
the limiter while its own documentation said it counted before it.

## What changed in the code

The window is now two, in a new pure-Kotlin class:

- `FrameRateWindows` — `arrived` (every frame the camera handed the analyzer,
  limiter or not) and `delivered` (frames the limiter let past). Extracted from
  `CameraManager` so it can be tested without Android types, the same rule
  `AdaptiveResolutionGovernor` and `FrameArrivalAccount` already follow.
- `arrival.observe(...)` is folded at the **top** of `analyzeFrame`, above the
  limiter; `delivered` is folded after it, so only accepted frames reach it.
- `arrived_fps` is published next to `measured_fps` in `/status.json`.

`rollFpsWindow`, `publishRate`, `FPS_WINDOW_MS` and the `AtomicLong` fields were
removed from `CameraManager`; `resetFpsWindow()` delegates to `FrameRateWindows`.

### Three bugs found while doing it

1. **The closing frame was counted in the window it closed.** Its timestamp sits
   at `nowMs`, outside `[start, nowMs)`, so the rate was `N/(N-1)` — about +6% at
   17 fps, the same order as the gaps the governor acts on.
2. **`0L` was the "window not started" sentinel.** It collides with a real
   timestamp of 0, which silently swallowed the first frame. Production uses
   `currentTimeMillis` and never hits it, which is exactly why it needed a test.
3. **The call sites were wrong before the windows were.** Arrival was first
   folded after the limiter, where a refused frame returns before reaching it —
   so the two windows stayed equal in production (`arrived_fps == measured_fps
   == 11`, measured). All 13 class-level tests were green while the defect was
   live, because the bug was never in `fold`; it was which frames reached it.
   Fixed by folding arrival above the limiter. A call-site test now guards it and
   goes red if the wiring is reverted.

## Measured, on the F3311, with a live `/video` viewer

Windows are now correct and clearly separated:

```
target  entered/s  pub/s  early/s  arrived_fps  measured_fps
    15       14.1    11.4     2.6         15            11
    15       14.1    10.8     3.2         14            10
     5       15.0     4.6    10.4         16             5
```

Before the change both read the same value, so `arrived_fps` was not measuring
anything.

### But the resolution still degrades — this pass did not fix that

`1280x720` now **holds for about 33 s**, then drops to `960x540`:

```
  t  rung        src      arrived  measured
  0  1280x720  1280x720       15        11
  4  1280x720  1280x720        8         8
 ...  (33 s steady at measured 8-9 against a target of 15)
 35  960x540    960x540       -1         8
 37  960x540    960x540       15        14
```

`oscillations=0`, `fps_relief_steps=0`, and no `OcuBeaCam` log lines at all.

Reading the governor: `downRatio=0.60`, `downHoldMs=3_000`, `settleMs=3_000`,
`cooldownMs=10_000`. At 1280x720 the device delivers **8–9 fps against a request
of 15 — ratio 0.55, below `downRatio`**. So the downgrade is the governor working
exactly as written: at that size the phone really does miss the target, for 3
seconds running. `oscillations=0` because `upRatio=0.90` is never reached — after
the downgrade delivery recovers to 14, but 14/15 = 0.93 sits just above the band,
so it neither climbs nor oscillates. It parks one rung down.

That reframes the original diagnosis. The ~17 fps ceiling was measured at small
rungs; **at 1280x720 this phone delivers ~8–9 fps**, and the ladder is reacting to
a real shortfall rather than to a mismeasured one. Whether 1280x720 should be
honoured on this hardware is a product decision, not a bug: either the request
should be honoured and the ladder should not second-guess it, or `downRatio` has
to account for the fact that bigger rungs legitimately deliver less.

Not resolved here, and deliberately so — the two options differ in what the user
promises the viewer.

## Second measurement: the downgrade happens in the first 3 seconds

Sampling `frames_entered` once a second through a rebind to 1280x720:

```
  t= 0.0  entered=33593 (+0)   rung=960x540  arrived=14 measured=13
  t= 1.2  entered=33593 (+0)   arrived=-1              <- camera closed, no frames
  t= 2.5  entered=33602 (+9)   arrived=-1
  t= 3.7  entered=33620 (+18)  arrived=15    measured=13   <- flowing again
  t= 5.9+ entered +16..18 per second, steadily, for 25 s
```

The camera is dead for roughly 2–3 s, then holds a steady **16–18 entered/s** —
above the 15 fps request. At 960x540 the device comfortably exceeds the target.

So the sequence is: `setQuality` rebinds, the camera is closed for ~2.5 s, the
governor's rate windows close across that dead time, `ratio` reads far below
`downRatio = 0.60`, `downHoldMs = 3_000` elapses while the camera is still coming
up, and the ladder steps down — **before the new resolution has had a single
steady window at all**.

`onRebind()` guards this with `settleMs = 3_000`, but the window that closes
during the dead time and the one that closes just after it are both *inside* the
settle window's shadow: `observe()` returns NONE while settling, and by the time
settling ends the `badSinceMs` timer has already been started by a reading that
spanned the gap.

This is the actual remaining defect, and it is not a judgement call about
whether 1280x720 is achievable. It is that **the decision is made on a window
that spans a period in which the camera was not running at all.**

The margin is the problem: the dead time measures **2.6 s** and `settleMs` is
**3 s**, so settling ends 400 ms after the camera comes back. The first window
the governor then judges spans only the first fraction of a second of real
frames — 9 frames where 15 were asked for — and reads `ratio = 0.60`, exactly on
the threshold. `downHoldMs` then expires while the device is still climbing back,
and the ladder steps down on a reading that describes the ramp, not the ceiling.

Two things this pass verified, which together rule out the obvious explanations:

```
960x540 BEZ widza : entered/s=14.6
960x540 Z   widzem: entered/s=14.6
640x360 BEZ widza : entered/s=14.6
```

The device holds **14.6 entered/s at every rung, with and without a consumer.**
The analyzer is not the bottleneck, the sensor is not the bottleneck, and JPEG/HTTP
are not the bottleneck. The earlier ~8 fps readings were windows straddling a
rebind, not a real rate — that reading has been withdrawn.

So both candidate policies are the wrong lever:

- **"a rebind may never degrade"** would remove the ladder's ability to respond to
  a genuine shortfall after a resolution change, which is the one case it exists
  for.
- **"try again only after a full settle"** lengthens the delay but does not change
  *which window* is judged; the decision still lands on the ramp.

The fix belongs in the timing: settling must outlast the ramp, not just the
camera's close/open.

## The actual cause, from the app's own log

Polling from outside could never show this, because the POST blocks
`/status.json` for the length of the rebind — every reading a sampler takes in
the first seconds is a stale snapshot, and by the time it can read, the decision
has already been made. Reading the app's own log instead:

```
I/OcuBeaCam: adaptive: anchored at 1280x720 (user asked 1280x720)
W/OcuBea   : Camera closed by device (code 0)
W/OcuBeaCam: Reopening camera after 1000ms: Camera closed by device (code 0)
I/OcuBeaCam: adaptive: DOWN 1280x720 -> 960x540 (7 fps vs 15 requested)
```

`7 fps vs 15 requested` — ratio 0.47, deep under `downRatio`. Then the rate
recovers immediately and stays high:

```
ratio  arrived  measured   rung
0.533     15        8    960x540
0.867     15       13    960x540
1.000     15       15    960x540
```

So the device sustains the target once it is up. The downgrade is decided on the
ramp, exactly as the timing analysis predicted, and it never comes back because
`upRatio = 0.90` needs a sustained surplus and the device sits right at the target
rather than above it.

`governor_ratio` is now published in `/status.json` so this is readable without
logcat in future: a `rung` below `user_rung` with a ratio near 1.0 means the
decision was made on a ramp, not on a slow device.

## A separate defect found on the way: rebind was never on the main thread

`setQuality` and `setFrontFacingCamera` called `rebind()` directly, and both are
reached from HTTP worker threads. CameraX requires `bindToLifecycle` on the main
thread, so **every resolution or camera-flip change posted over the network
threw**:

```
W/OcuBea   : Rebind failed: Not in application's main thread
W/OcuBeaCam: Reopening camera after 1000ms: Rebind failed: Not in ...
W/MessageQueue: Handler (SurfaceTexture$1) sending message to a Handler on a
                dead thread
```

The watchdog then retried the same failing rebind **once a second, forever**.
Fixed by `rebindOnMainThread`, which posts to the main looper when called off it
and runs inline when already there; the `wantStreaming` and provider guards stay
in `rebind` so a queued rebind still honours them. Verified on device: the
"Not in main thread" line is gone, and `anchor()` now actually applies — the log
shows `anchored at 1280x720` followed by a real downgrade, which could not happen
before, because the rebind that would have applied it never ran.

This also explains the confusing measurement in the first pass: the resolution
sweep appeared to show `rung` stuck at one value regardless of the setting. The
setting was being applied to the ladder, but the rebind that would have honoured
it was throwing, so the camera stayed where it was.

Verified separately: at 960x540 with no viewer, `entered` holds 14.3–14.6/s
steadily with `null_bitmaps` tracking it — the analyzer is not the bottleneck.


## Also note

`/settings/resolution?set=WxH` returns `Ok` and changes nothing. The working
endpoint is `/settings/video_size?set=WxH`, which reaches `setQuality`. A sweep
driven by the former measures a camera that never moved, and reads as "the
setting is ignored".

## The ramp defect, and why the governor now refuses to judge a window it cannot trust

### What was actually wrong

The phone log told the whole story, and it contradicted the external sampler:

```
I/OcuBeaCam: adaptive: anchored at 1280x720 (user asked 1280x720)
W/OcuBea   : Camera closed by device (code 0)
W/OcuBeaCam: Reopening camera after 1000ms: Camera closed by device (code 0)
I/OcuBeaCam: adaptive: DOWN 1280x720 -> 960x540 (7 fps vs 15 requested)
```

The camera was closed by the device and reopened. The rebind leaves it dead for
about **2.6 s**, and `settleMs` was 3 s, so settling ended roughly 400 ms after the
camera came back. The first window the governor then judged covered that 400 ms.
It saw 7 frames where 15 had been asked for, and the ladder stepped down.

The readings straight after the downgrade show what was really happening:

```
governor_ratio  arrived_fps  measured_fps  rung
0.533           15          8             960x540
0.867           15          13            960x540
~1.0            15          15            960x540
```

The phone sustained 15 fps at the rung the user had asked for, all along.

Two separate mistakes had to be corrected to see this at all:

1. **`/settings/resolution?set=WxH` returns `Ok` and does nothing.** The real path
   is `/settings/video_size?set=WxH`. Earlier sweeps had been changing nothing and
   the "it ignores the setting" reading was an artefact of the wrong endpoint.
2. **The rebind was throwing off the main thread.** Every quality change from the
   HTTP worker logged `Rebind failed: Not in application's main thread`, so the
   ladder was never actually applying the anchor. Fixed by routing
   `setQuality()` and `setFrontFacingCamera()` through `rebindOnMainThread`.
   After the fix the count of those errors is 0.

### A wrong conclusion I recorded, and what corrected it

Late in this pass I wrote that `1280x720` yields zero frames on the F3311,
because `null_bitmaps` equalled `frames_entered` and logcat showed
`ImageReader-1280x720 ... in a disconnected state`. I proposed a format ceiling
and said the top rung was unusable on this device.

**That was wrong, and the evidence for it was a measurement that never ran.**

The consumer I meant to be reading from wrote **zero bytes** — `/tmp/consumer.mjpeg`
did not exist. I read that as "the pipeline delivered nothing", when it meant "my
capture produced no file". Every number quoted alongside it had been sampled with
**no consumer attached**.

Re-measured with a consumer actually attached (`viewers=1`):

```
/video at 1280x720, 20 s : http=200, 26 335 679 bytes
  176 SOF headers, 176 multipart boundaries, every one 1280x720
  40/40 frames unique, 256 distinct byte values   -> a moving picture
```

And the counters separate cleanly once there is somewhere for the frames to go:

```
entered=5677  null_bitmaps=4543  published=906   null/entered = 0.80
viewers=1, 31 MB delivered
```

So `null_bitmaps` and `frames_published` describe the **analyzer**, not the client.
80% of frames are discarded by the analysis path while the MJPEG consumer receives
a correct, changing 1280x720 image. Two independent numbers that look like the same
subject and are not.

The lesson is not subtle but keeps costing time: *a reading taken without the
consumer attached is not a reading about delivery.* It also matches the trap this
class of bug keeps setting — a green result from something that did not execute
(`queueBuffer: fps=`, an empty output file) looks exactly like a result.

`tools/check_mjpeg.py` exists so the stream itself is checked — SOI, SOF
dimensions, boundary count, and per-frame uniqueness — instead of inferring it
from a counter.

Also worth stating plainly: `/status.json` **blocks while a rebind is in flight**,
so any external sampler reading across a rebind sees a stale snapshot. The
governor's own decision has to be read from the app log, not from an outside
poller.

### The fix

Settling is the wrong clock. "How long ago was the rebind" cannot describe
whether the camera has reached its rate -- a device that takes 8 s to get there
passes a 3 s timer and still hands the governor a ramp.

What matters is the window behind the reading:

```kotlin
fun isRamping(nowMs: Long, windowStartMs: Long): Boolean =
    nowMs - windowStartMs < steadyWindowMs
```

A reading is admitted only once its window spans at least one target-second of
real frames. `FrameRateWindows` publishes `startOfWindowMs` for exactly this, and
`CameraManager` passes it as `windowStartMs` on every `observe()`. `windowStartMs
== 0` means "unknown span" and preserves the old behaviour, so an older caller is
not silently protected by a guard it never asked for.

Cheap by construction: two comparisons, and only when a window closes.

### Tests

Four tests in `AdaptiveResolutionGovernorTest`:

- a 400 ms window holding 7 fps against a request of 15 must not degrade, and the
  rung must still be `1280x720` -- the measured failure, verbatim
- a *full second* of the same rate still degrades, so the guard is not a blanket
  refusal and the ladder is not decorative
- a refused short window must not leave `badSinceMs` running, or the ramp banks
  blame and the next genuine full window degrades on no evidence of its own
- a caller supplying no window start keeps the old behaviour

Two of them initially passed for the wrong reason and had to be fixed:

- both downgrade tests asserted on the *last* of several windows, but a commit
  starts a fresh settle window, so everything after the downgrade returns `NONE`
  by design. They now assert that the decision *happened*.
- both called `onRebind(0L)`, which sets `lastChangeMs`, and `cooldownMs` is 10 s
  -- so every window was refused by the cooldown and the tests proved nothing.

### A mutation harness that reported three false hits

The first harness for this guard reported three of four mutations "caught" for a
guard that was in fact unprotected. Gradle served `:app:testDebugUnitTest`
`UP-TO-DATE` from cache, never recompiled, and the suite ran against the original
production code and came back green.

`tools/mutate_ramp_guard.py` now enforces what that harness did not:

- `--rerun-tasks` on every run, so the suite genuinely recompiles
- a compile error is reported `INCONCLUSIVE`, never as a caught mutation
- the tree is restored in a `finally` and the SHA-256 of the restored file is
  compared with the original

The restore matters in practice: the harness was killed by a 300 s tool timeout
while a mutation was in place, leaving `CameraManager.kt` mutated on disk. It was
recovered from backup and verified before anything else was run.

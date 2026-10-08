# Working on OcuBea

## Architecture
Native App UI in Kotlin
Preact-based WebUI hosted by the app

## Comments

**Clean code beats comments. A comment is a last resort, not an explanation.**

The test: could the code say this better? If a comment restates what the next
line does, delete it. `// increment i` above `i++` is noise, and noise trains
readers to skip every comment including the ones that matter.

A comment earns its place only when it carries something the code cannot:

- **Why, not what.** `if (!started.compareAndSet(false, true)) return` needs a
  note that `started` is cleared only by `stopEverything()`, which a Stop
  deliberately does not call, so this returns immediately having done nothing.
  The code says what it does. Only the comment says it is a bug.
- **A measurement, with the numbers.** "CRC-8 sits at pos+7, not pos+3" is worth
  more than any paragraph about FLAC headers, because it was measured and the
  next person can check it.
- **A constraint that looks arbitrary.** `minSdk = 23` is why `lintDebug` is a
  CI step and not a suggestion.
- **A rejected approach, so it is not tried again.** "`-g 1`, `-qp 0` and
  `-force_key_frames` all still give NAL type 1; ffmpeg will not emit an IDR on
  request." That comment saves an afternoon.

Do not narrate a diff in the source. The commit message is where "what changed"
belongs, and it has room for the reasoning that would otherwise bloat the file.

Two rules that follow:

- Do not comment out code. Delete it or fix it. A commented-out block is a lie
  about whether that path still works.
- If a comment is long enough to need a paragraph, the logic is probably wrong.
  Rewrite the code so the comment fits in a line.

## Gates

**A gate that cannot fail is worse than no gate, because it is trusted.**

Every new check ships with a mutation that reproduces the defect it was written
for, and the mutation must turn it red. If you cannot write one, you have not
found the defect yet, you have only guessed at it.

Three ways a gate lies, all of which happened here:

- **It reports a correct verdict but always exits 0.** `check_mjpeg.py` printed
  accurate verdicts on a 100x garbage buffer and returned 0 anyway.
- **It reads its own output as success.** A gate collected ffmpeg's stderr into
  a notes list and went green while ffmpeg printed `invalid residual` for every
  frame. Byte counts prove nothing; the decoder rejecting packets is the signal.
- **It tests a stale artifact.** The WebUI gates read the committed bundle, and
  `src/` had gained 1452 lines while all three stayed green. `bundle_verify.js`
  exists so nobody has to remember.

Run the mutation before you trust the green:

```bash
python3 tools/verify_audio_containers.py --mutate --fixture-dir <captures>
python3 tools/verify_landscape_layout.py --mutate
node tools/bundle_verify.js --device=HOST:PORT
```

`--mutate` that prints `0/2` while reporting detections is a broken counter, not
a pass. Count the prints.

## Proof

**`BUILD SUCCESSFUL` is not evidence that anything works.** It is evidence that
the compiler was satisfied.

- A `LinearLayout` that overflows is clipped, not erroneous. The landscape
  control column lost motion, screen-off, clips, settings, perf, exit and kiosk
  that way, with no lint warning. Bounds are the only truth: `uiautomator` said
  13 of 13 controls on screen, a screenshot said they were cut off. Measure the
  bounds.
- `configChanges="orientation|screenSize"` means the platform neither recreates
  the Activity nor re-inflates its layout. `layout-land/` is dead code until
  something calls `setContentView` again.
- `startService` from the background throws on API 26+, and `catch (_: Exception)
  {}` swallows it so the button looks broken instead of crashing.

**A counter needs a delta.** Cached `fps` and `frames` stay populated after the
camera stops. `frames 286` then `frames 443` means the camera restarted; one
reading of `fps: 9` means nothing at all.

**A screenshot is a witness, not a judge.** Vision analysis reported clipped
buttons that `uiautomator` then proved were fully on screen. When they
disagree, geometry wins.

**Measure before you name a cause.** "The audio is broken" is a symptom. The
first fix here turned a healthy capture red, because ffmpeg reports a stream
cut mid-frame exactly the way it reports corruption, byte for byte. Cut a known
clean file at two offsets and watch the same three lines appear. That is what
proved it was truncation, and it is why the check now re-decodes with the tail
removed instead of pattern-matching the message.

## Android 6

`minSdk = 23` is a hard constraint, not a policy. `compileSdk` resolves symbols
introduced later, so a call to an API from 2024 compiles cleanly and throws
`NoSuchMethodError` on the device in the drawer. Four such calls had shipped:
`isHardwareAccelerated` (29), `computeIfAbsent` (24), `updateAndGet` (24),
`withInitial` (26). `lintDebug` runs in CI for exactly this.

## Device work

- Always `adb -s SERIAL`. Two phones share one ADB server.
- **Never trust `adb install ... Success`.** Check the APK's MD5 on both ends and
  read `lastUpdateTime` off the phone. A phone that vanished mid-install reported
  success.
- Incrementally built resources go stale without notice. Use `--rerun-tasks` and
  check the artifact's mtime when a gate must see a change.
- One Gradle at a time. Two processes sharing `app/build/` fail with
  `Conflicting import, imported name 'Test' is ambiguous`, which reads like a
  missing import and is not.
- Never write `app/src/main/assets/index.html` by hand. It is generated from
  `src/app.jsx`, `src/ui.css`, `src/i18n.js`. Edit the source, run
  `node tools/bundle_verify.js --write`, then verify.
- `assembleDebug` does not run `node build.mjs`. Nothing in the Gradle build
  invokes it, which is why the bundle is committed and why
  `bundle_verify.js` is a CI step.
- Before reading the UI make sure to wake the screen with `KEYCODE_WAKEUP`, then
  `KEYCODE_MENU`. A sleeping screen yields 6 KB blank screenshots.

## Commits

Don't state what can be seen from the diff

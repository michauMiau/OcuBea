# Feature parity: Preact UI vs the old WebUI

Audited against the old UI **served live on 192.168.1.122** (53 674 B, 1212
lines), not against memory. The old UI is the reference; every row below is a
function it has and the Preact build does not.

Audited 2026-09-30. Buttons: old 20, Preact 4. Endpoints: old 22, Preact 12.

## Optics — missing entirely (section does not exist in Preact)

| Feature | Endpoint | Verified on .122 |
| --- | --- | --- |
| Zoom slider | `/ptz?zoom=` | 200 |
| Autofocus (centre) | `/focus?x=0.5&y=0.5` | 200 |
| Effect select: None/Monochrome/Negative/Sepia/Night | `/settings/effect?set=` | 200 |
| Quality select: QVGA/VGA/720p/1080p | `/settings/quality?set=` | 200 |
| FFC (front-camera flip) toggle | `/settings/ffc?set=toggle` | 200 |

## Security camera — missing entirely

| Feature | Endpoint | Verified on .122 |
| --- | --- | --- |
| Night vision toggle | `/settings/night_vision?set=` | 200 |
| Motion detection toggle | `/settings/motion_detection?set=` | 200 |
| Motion sensitivity slider | `/settings/motion_sensitivity?set=` | 200 |
| Pre-record seconds slider | `/settings/pre_record_seconds?set=` | 200 |
| Max clip seconds slider | `/settings/max_clip_seconds?set=` | 200 |

## Clips — list exists, every per-clip action missing

| Feature | Endpoint | Contract |
| --- | --- | --- |
| Record now (30s) | `/clips/record?name=<n>` | 400 "invalid clip name" without `name` |
| Stop recording | `/clips/record/stop?name=<n>` | same |
| Apply limits (prune) | `/clips/prune?name=<n>` | same |
| Delete all | `/clips/clear?name=<n>` | same |
| Delete one | `/clips/<name>` DELETE | 200 |
| Download one | `/download?name=<n>` | needs the right name, not `x` |
| Close preview modal | client only | — |

Real clip name shape: `klip_2026-09-30_01-10-37.mp4`.

## Audio — missing entirely

Radio group `ac_none` / `ac_opus` / `ac_aac` / `ac_amrnb`, each with a one-line
explanation. `POST /audio/codec` (404 on GET — it is a POST route).

## Stream controls — missing

| Feature | Endpoint |
| --- | --- |
| HLS quality profile | `/hls/profile` → `profile=default segment_ms=250 keyframe_sec=0 sync=3 buffer=6` |
| Low latency toggle | present in Preact |

## Closed 2026-09-30

All of the above is now implemented and measured against 192.168.1.122:

- Optics section: zoom, torch, autofocus, flip, effect select, quality select, viewers
- Image section: jpeg quality, bitrate, frames, effect
- Security section: night vision, motion detect, sensitivity, pre-record, max clip
- Audio section: codec select (none/opus/aac/amrnb), current codec, available list
- Clips: record 30s, stop, refresh, apply limits, delete all, per-clip delete, size shown
- Recordings: refresh, delete all, per-clip delete, size shown
- HLS quality readout (bHQ) from /hls/profile
- 16 controls with ids, up from 4; 9 sections, up from 6
- i18n keys 56 -> 110, both languages complete, 0 untranslated

Verified on the phone, each change confirmed by reading status.json back:
effect sepia->negative->sepia, night_vision toggle, front_camera toggle,
motion_sensitivity 5->8, focus 200, /clips/prune with a real name returns
{"removed":0,...}. All settings restored afterwards.

## Already present in Preact

Start/stop, mode MJPEG/HLS, low latency, torch, snapshot, zoom, flip,
record toggle, recordings list + delete, clips list, device panel, status panel,
API token, language switch, HLS→MJPEG fallback, favicon, HLS warm-up.

## Rules for closing this list

- An endpoint is done when the **phone** answers 200 and the UI reflects the
  new state on the next poll. A 200 that changes nothing is not done.
- Every new control needs a Polish string in `STR.pl` or the i18n check fails.
- New settings must be read back from `status.json`/`/clips`, not from local
  component state — the phone is the source of truth.

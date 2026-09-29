# OcuBea — IP Webcam Compatible API

Base URL: `http://<device-ip>:8080`

Fully compatible with the [IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam) Android app API.

---

## Streaming & Snapshots

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/video`, `/mjpeg`, `/stream` | `GET` | MJPEG stream (`multipart/x-mixed-replace`) |
| `/shot.jpg`, `/snapshot.jpg` | `GET` | Single JPEG snapshot (binary) |
| `/audio.wav` | `GET` | WAV audio from microphone (16-bit PCM mono 44.1 kHz) |
| `/audio.aac` | `GET` | alias of `/audio.wav` — **WAV bytes**, not AAC |
| `/audio.opus` | `GET` | alias of `/audio.wav` — **WAV bytes**, not Opus |

---

## Camera Control

### Focus

```bash
# Trigger autofocus (center)
POST /focus

# Release continuous AF
POST /nofocus
```

Returns: `text/plain: ok` or `error: <message>`

### Front/Back Camera Switch

**Legacy API:**
```bash
POST /api/camera?set=true   # front camera
POST /api/camera?set=false  # back camera
```

**IP Webcam compatible:**
```bash
POST /settings/ffc?set=on    # front camera
POST /settings/ffc?set=off   # back camera (default)
```

### Zoom (PTZ)

```bash
# Digital zoom — Pan/Tilt not supported (fixed mount)
POST /ptt?zoom=2.0
```

Returns: `text/plain: ok`

---

## Settings

Bulk settings via single POST request:

```bash
POST /settings
  quality=1080&night_vision=on&ffc=off
```

Individual settings (IP Webcam compatible):

| Endpoint | Parameters | Description |
|----------|-----------|-------------|
| `POST /settings/quality?set=<n>` | `480`, `720`, `1080` | Resolution: QVGA, HD720, FullHD |
| `POST /settings/night_vision?set=on\|off` | `on`, `off` (default) | Low-light enhancement (LUT, applied per frame) |

---

## Status & Info

```bash
# Get current device/camera status as JSON
GET /status.json
GET /info
```

Response example:
```json
{
  "status": "ok",
  "camera_active": true,
  "streaming": false,
  "resolution": "1280x720",
  "zoom_level": 1.0,
  "focus_distance": 0.0
}
```

---

## Torch/Flashlight

```bash
POST /torchon    # enable flashlight
POST /torchoff   # disable flashlight (default)
```

There is no `/api/torch` endpoint — earlier versions of this document
listed one, but the server only ever routed `/torchon` and `/torchoff`.

Response: `application/json` with torch state and camera_id.

Requires `CAMERA` permission on Android 6+ (granted at app install).

---

## Examples

```bash
# Start MJPEG stream → pipe to VLC or ffmpeg
curl -o- http://192.168.1.10:8080/video | vlc --

# Take a snapshot and save it
curl -o shot.jpg "http://192.168.1.10:8080/shot.jpg"

# Switch to front camera
curl -X POST "http://192.168.1.10:8080/settings/ffc?set=on"

# Set resolution to 720p
curl -X POST "http://192.168.1.10:8080/settings/quality?set=720"

# Enable flashlight
curl -X POST "http://192.168.1.10:8080/torchon"

# Trigger autofocus
curl -X POST http://192.168.1.10:8080/focus

# Get status JSON
curl http://192.168.1.10:8080/status.json | jq .
```

---

## Notes

- **Port**: 8080 (`StreamServer.DEFAULT_PORT`)
- **Audio streaming** (`/audio.wav`) — implemented, one-way: microphone to
  client only. There is no playback path, so a client cannot talk back to the
  phone. `/audio.aac` and `/audio.opus` are aliases that also return WAV bytes.
- **Night vision** — implemented as a cached per-frame LUT, not a stub
- **PTZ pan/tilt** — not supported (fixed mount), only zoom via digital scaling

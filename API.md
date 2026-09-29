# OcuBea — IP Webcam Compatible API

Base URL: `http://<device-ip>:8080`

Fully compatible with the [IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam) Android app API.

## Finding the phone

The phone is on **DHCP**, so its address changes between sessions and between
WiFi networks. There is no fixed default to put in an example, and hardcoding
one is a trap: the address you remember may have been reassigned to a laptop by
the time you use it.

The app does **not** advertise a name. There is no mDNS/Bonjour, no hostname
registration, no QR code and no pairing flow in the code — the only network
discovery it implements is ONVIF WS-Discovery on UDP 3702, which only ONVIF-aware
clients (NVRs) use. So there is nothing to resolve and no name-based URL.

Look the address up on the phone itself:

- **On screen** — the app shows its own full URL, e.g. `http://192.168.1.124:8080`,
  on the main screen and on the settings screen (`MainActivity.kt:320-322`,
  `SettingsActivity.kt:343-353`). This is the authoritative answer; the app reads
  the live interface, not a stored value.
- **Remotely, if you have it already** — `GET /sensors.json` reports it as
  `network.ip` (`DeviceSensors.kt:49`).

If you have neither, the router's DHCP client list will show which address it
handed to the phone. Pin a static lease if you want an address that stops moving.

---

## Streaming & Snapshots

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/video`, `/mjpeg`, `/stream` | `GET` | MJPEG stream (`multipart/x-mixed-replace`) |
| `/shot.jpg`, `/snapshot.jpg` | `GET` | Single JPEG snapshot (binary) |
| `/audio.wav` | `GET` | WAV audio from microphone (16-bit PCM mono 44.1 kHz) |
| `/audio.aac` | `GET` | **not implemented** — HTTP 501, plain text explaining why |
| `/audio.opus` | `GET` | **not implemented** — HTTP 501, plain text explaining why |

`/audio.aac` and `/audio.opus` used to be aliases that returned WAV bytes under
the name you asked for. That was wrong — a client requesting Opus and receiving
WAV cannot tell, and would mis-parse. They now fail loudly instead
(`StreamServer.kt:439-447`). The AAC and Opus encoders themselves work on the
test phone (`MediaCodec` produced real output when probed, and
`/status.json` → `audio.available_list` reports what the device proved it can
encode), but the encoder is not wired to the stream yet. **Only `/audio.wav`
delivers audio today.**

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

`$CAM` below is the phone's current address — see [Finding the phone](#finding-the-phone)
above. It is a variable, not a constant: the address moves.

```bash
CAM=192.168.1.124   # read it off the phone's screen; not a fixed default

# Start MJPEG stream → pipe to VLC or ffmpeg
curl -o- http://$CAM:8080/video | vlc --

# Take a snapshot and save it
curl -o shot.jpg "http://$CAM:8080/shot.jpg"

# Switch to front camera
curl -X POST "http://$CAM:8080/settings/ffc?set=on"

# Set resolution to 720p
curl -X POST "http://$CAM:8080/settings/quality?set=720"

# Enable flashlight
curl -X POST "http://$CAM:8080/torchon"

# Trigger autofocus
curl -X POST http://$CAM:8080/focus

# Get status JSON
curl http://$CAM:8080/status.json | jq .
```

---

## Notes

- **Port**: 8080 (`StreamServer.DEFAULT_PORT`)
- **Audio streaming** (`/audio.wav`) — implemented, one-way: microphone to
  client only. There is no playback path, so a client cannot talk back to the
  phone. `/audio.aac` and `/audio.opus` exist as routes and the device may
  advertise them in `audio.available_list`, but they return **HTTP 501**: the
  encoders are proven to work, they are just not wired to the stream. WAV is the
  only working audio path.
- **WAV is expensive**: measured at 86,868 B/s — about 695 kbps, so roughly
  **5.2 MB per minute per client**. It is uncompressed 16-bit PCM at 44.1 kHz
  (`AudioStreamManager.SAMPLE_RATE = 44100`). That is why AAC and Opus were
  wanted; until they are wired, budget bandwidth accordingly.
- **Audio client cap is 8.** The HTTP pool is 12 threads
  (`BoundedAsyncRunner.DEFAULT_MAX_THREADS`), and every chunked audio response
  holds one for the life of the connection — the path cannot be handed a
  separate pool, because NanoHTTPD's `ClientHandler` exposes neither the URI nor
  the socket publicly. Excess clients get `503 Too many audio clients`
  (`StreamServer.kt:388-393`). A client that connects and never reads used to be
  able to take the whole server down with it; the ring buffer now drops such a
  client instead of pinning a thread.
- **Night vision** — implemented as a cached per-frame LUT, not a stub
- **PTZ pan/tilt** — not supported (fixed mount), only zoom via digital scaling

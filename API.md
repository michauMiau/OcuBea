# OcuBea — IP Webcam Compatible API

Base URL: `http://<device-ip>:8080`

Fully compatible with the [IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam) Android app API.

## Finding the phone

The phone is on **DHCP**, so its address changes between sessions and between
WiFi networks. There is no fixed default to put in an example, and hardcoding
one is a trap: the address you remember may have been reassigned to a laptop by
the time you use it.

The app does **not** advertise a name. There is no mDNS/Bonjour, no
`NsdManager`, no hostname registration, no QR code and no pairing flow in the
code — the only network discovery it implements is ONVIF WS-Discovery on
UDP 3702, which only ONVIF-aware clients (NVRs) use. So there is nothing to
resolve and no name-based URL.

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
| `/audio.wav` | `GET` | WAV audio from microphone (16-bit PCM mono 44.1 kHz, `audio/x-wav`) |
| `/audio.aac` | `GET` | AAC audio (`audio/aac`, ADTS-framed). **The default and the one that works.** |
| `/audio.opus` | `GET` | Opus in Ogg (`audio/ogg; codecs=opus`). Works where the vendor encoder emits spec-compliant packets; not on the test phone — see below. |
| `/audio/codec` | `POST` | Store the audio codec preference: `codec=aac\|opus\|amrnb\|flac\|wav\|none` |

`/inband.aac` and `/talk` route to the same handler as the `/audio.*` paths.

## Audio codecs

The codec menu is **not** hardcoded. `AudioCodecProbe`
(`app/src/main/java/com/ocubea/server/AudioCodecProbe.kt`) asks
`MediaCodecList` which encoders exist and then confirms each one by actually
encoding a buffer; only codecs that emit real bytes become menu entries. On the
test phone (Redmi Note 10 Pro, MIUI, Android 13) the menu is Opus 32 kbps,
AAC 64 kbps, AMR-NB 12 kbps, WAV raw PCM, FLAC.

MP3 is deliberately absent: no Android device ships an MP3 *encoder* in
`MediaCodecList` — it is decode-only. Offering it would be a lie.

The extension the client asks for wins, so `/audio.aac` gets AAC and
`/audio.opus` gets Opus. The request is honoured only when the device proved it
can encode that codec; otherwise the stored preference, then the probe default,
then WAV is served. Neither path ever returns PCM under a compressed codec's
name — an earlier version did exactly that, and a client asking for Opus could
not detect it.

**AAC works.** `GET /audio.aac` returns `Content-Type: audio/aac` with
`Transfer-Encoding: chunked`; the bytes are raw AAC LC wrapped in ADTS frames
(the stream starts `0xFF 0xF1`) and ffmpeg decodes them to PCM. Measured
**~66 kbps**. AAC is the default rather than Opus because AAC encoders exist on
every Android this app supports, while `c2.android.opus.encoder` only landed in
Android 11.

**Opus works.** `c2.android.opus.encoder` on this vendor HAL emits packets
whose TOC byte is `0x78` — Opus configuration 15, which is code 3, the CELT-only
mode. That is a legal *decoding* configuration; RFC 6716 forbids it only for
24 kHz inpback signalling. An earlier version of this page blamed the HAL and
called it illegal, which was wrong. The real defect was in this app: the Ogg
stream had `OpusHead` but no `OpusTags`, and ffmpeg's ogg demuxer walks to the
end of the header chain before opening a stream, so it fell off the end of the
first page and reported "Header processing failed" for the whole file — CRC,
sequence numbers and granule positions were all valid and none of that was the
issue. With `OggPage.opusTags()` added, ffmpeg decodes the stream to PCM with
zero packets rejected, at roughly 42 kbps transport.

`audio.undecodable` reports mime types this device encodes but whose own
`MediaCodec` decoder would not take the bytes back. It is a warning about the
device's own software decoders, not a verdict on the stream, and it never
removes a codec from the menu — on the validation phone it lists
`audio/mp4a-latm` and `audio/flac`, both of which play perfectly under ffmpeg.

`/status.json` → `audio` reports `enabled`, `clients`, `codec`,
`available_list` (an array of `{id,label,bitrate,container,note}` for the
WebUI picker to iterate), `undecodable` (mime strings this device encodes but
cannot itself decode), `encoded` (encoder counters: `clients`, `feed_calls`,
`feed_misses`, `packets_out`, `empty_out`) and `available` (a one-line human
summary). `feed_misses` climbing with `packets_out` at zero separates "no PCM
reached the encoder" from "the codec produced nothing".

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

Response example (abridged — the real object is much larger):
```json
{
  "status": "ok",
  "app": "OcuBea",
  "camera_active": true,
  "fps": 14,
  "target_fps": 15,
  "frames": 12345,
  "pipeline": { "last_error": "none" },
  "hls": { "active": false },
  "audio": {
    "enabled": true,
    "clients": 0,
    "codec": "aac",
    "available_list": [
      { "id": "opus",  "label": "Opus 32 kbps", "bitrate": 32000, "container": "webm/opus", "note": "..." },
      { "id": "aac",   "label": "AAC 64 kbps",  "bitrate": 64000, "container": "aac",       "note": "..." },
      { "id": "amrnb", "label": "AMR-NB 12 kbps","bitrate": 12200, "container": "3gpp",      "note": "..." },
      { "id": "wav",   "label": "WAV (surowy PCM)", "bitrate": 706000, "container": "wav",    "note": "..." },
      { "id": "flac",  "label": "FLAC",          "bitrate": 0,     "container": "flac",      "note": "..." }
    ],
    "undecodable": ["audio/opus"],
    "encoded": { "clients": 0, "feed_calls": 0, "feed_misses": 0, "packets_out": 0, "empty_out": 0 },
    "available": "aac=true opus=true amrnb=true flac=true default=aac"
  },
  "auth_required": false,
  "battery_level": 87
}
```

The `audio` block is the one to read when audio is quiet: `undecodable` names
codecs this device encodes but cannot itself decode, and `encoded.feed_misses`
rising while `packets_out` stays at 0 means PCM never reached the encoder.

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

`camera_active` is the field to trust. When it is `false`, `/video`, `/hls` and
`/startvideo` answer `503 Camera not streaming` and `/shot.jpg` answers
`204 No frame yet` — the server stays up and says so.

---

## Notes

- **Port**: 8080 (`StreamServer.DEFAULT_PORT`)
- **Audio streaming** — implemented, one-way: microphone to client only. There
  is no playback path, so a client cannot talk back to the phone. All of
  `/audio.wav`, `/audio.aac` and `/audio.opus` now serve the codec they name.
  Which codecs are available is a per-device question answered by
  `AudioCodecProbe` at runtime, not a fixed list — see **Audio codecs** above.
- **WAV is expensive**: measured at 86 868 B/s — about 695 kbps, so roughly
  **5.2 MB per minute per client**. It is uncompressed 16-bit PCM at 44.1 kHz
  (`AudioStreamManager.SAMPLE_RATE = 44100`). That is why AAC is the default:
  at ~66 kbps it is roughly ten times cheaper on the wire.
- **Audio client cap is 8** (`AudioAdmissionControl`); the ninth gets
  `503 Too many audio clients`. The HTTP pool is 12 threads
  (`BoundedAsyncRunner.DEFAULT_MAX_THREADS`), and every chunked audio response
  holds one for the life of the connection — the path cannot be handed a
  separate pool, because NanoHTTPD's `ClientHandler` exposes neither the URI nor
  the socket publicly. Each client also gets its own 64 KiB non-blocking
  `AudioRingBuffer`, so a client that connects and never reads is dropped
  rather than pinning a thread. Verified: 10 concurrent `/audio.aac` clients
  each received 45 056 bytes with no exceptions.
- **Camera state is reported truthfully.** `camera_active` in `/status.json` is
  the real `CameraState`, and `pipeline.last_error` names the failure. The
  recurring `Device error received, code 3` is `ERROR_CAMERA_DISABLED`, which
  CameraX 1.3.0 treats as terminal, so the app observes `CameraState` itself
  and reopens with backoff. While the camera is down, `/video`, `/hls` and
  `/startvideo` return `503 Camera not streaming` and `/shot.jpg` returns
  `204 No frame yet` — with the camera open, `/shot.jpg` returns 200 and about
  63 KB, at 12–15 fps on the test phone.
- **Night vision** — implemented as a cached per-frame LUT, not a stub
- **PTZ pan/tilt** — not supported (fixed mount), only zoom via digital scaling
- **No mDNS/Bonjour.** The app has no `NsdManager` discovery, no hostname
  registration and no pairing flow. The only discovery is ONVIF WS-Discovery
  on UDP 3702, for NVRs. See [Finding the phone](#finding-the-phone).

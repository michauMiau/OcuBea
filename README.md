# OcuBea

**Lightweight IP Camera Streamer for Android**

## Progress

### Current status

- [x] Basic MJPEG Streaming (/video)
- [x] WebUI — full dark theme with controls (torch, night vision, camera switch, zoom, quality, focus)
- [x] App UI — SettingsActivity with port, resolution, FPS, night vision, mic toggles
- [x] IP Webcam compatible API (status.json, /info, /shot.jpg, /focus, /ptz, /api/camera, /torchon, /torchoff)
- [x] Audio streaming (one-way, codec chosen per device: AAC, Opus, AMR-NB, WAV
      or FLAC. AAC is the default and works. Opus is offered by the probe but
      its packets are invalid on some vendor HALs — see the audio notes below)
- [x] Software Night Vision Enhancement
- [x] Configurable HTTP server port (default 8080, stored in SharedPreferences)
- [x] Motion Detection Recording (MJPEG-in-AVI to `/recordings`, fMP4 clips to `/clips`)
- [ ] Bidirectional Audio
- [ ] HTTPS Support
- [ ] Alternative codecs (H265, AVIF)
- [x] Background service / Foreground Service
- [x] Auto screen dim / keep screen on
- [x] ONVIF Support (Profile S)
- [x] Low-latency HLS streaming (hardware H.264, fMP4). Short segments and
      player-side buffer tuning — see the caveat below before expecting
      LL-HLS (RFC 8216bis) behaviour such as `EXT-X-PART` preloading.
- [x] Clip retention (age / size / count sweep)
- [x] Optional access token for the whole HTTP API
- [x] Full API Parity (core IP Webcam endpoints)

### Test-device status (2026-09-29)

The camera now reports its **real** state, and reopens itself when the device
kills it. The recurring failure — `Device error received, code 3` — is
**`ERROR_CAMERA_DISABLED`**, not `ERROR_CAMERA_DEVICE` (which is 4). CameraX
1.3.0 routes only codes 1 (`IN_USE`), 2 (`MAX_CAMERAS_IN_USE`) and 4 (`DEVICE`)
to its own `reopenCameraAfterError`; code 3 is treated as **terminal** and the
camera is closed for good. The app therefore observes `CameraState` itself and
reopens with backoff (`CameraManager.CameraStateObserver`,
`CameraManager.scheduleReopen`), so a device disable no longer needs a reboot
to clear.

When the camera is down the server stays up and says so rather than pretending:
`/status.json` reports `camera_active: false` and `pipeline.last_error`,
`/video`, `/hls` and `/startvideo` return `503 Camera not streaming`, and
`/shot.jpg` returns `204 No frame yet`. Note that the app's own main screen
still shows a green "live" line in this state — it reads fps and viewer counts,
not `camera_active` — so check `camera_active` remotely rather than trusting
the phone's screen.

Measured, not assumed. Frame rate depends on the phone, so both test devices
are listed rather than one flattering number:

| Device | Resolution | Real fps |
| --- | --- | --- |
| Redmi Note 10 Pro, Android 11 | 1280x720 | 12–15 |
| Sony F3311, Android 6, MediaTek | 1280x720 | ~10 |

`/shot.jpg` returns HTTP 200 with a real JPEG of about **63 KB** on the Redmi
and **57 KB** on the Sony.

The Sony number is a consequence of hardware, not a setting. Its camera reports
`feature-max-fps: 24`, so 24 is available; the 10 is what the pipeline
sustains at 720p. Dropping to 640x480 gives ~8. The `mono` colour effect costs
about 2 fps because it is applied per frame on the CPU.

Note that `/status.json` reports the frame rate the camera actually granted,
not the one that was requested. The two can differ, and a camera that quietly
ignores the request will otherwise show the requested number while delivering
something else.

### HTTP surface

Routing lives in a single `when` block — `StreamServer.route()`
(`app/src/main/java/com/ocubea/server/StreamServer.kt:114`). Read-only JSON is
delegated to `TelemetryHandler`.

| Area | Endpoints |
|---|---|
| Web UI | `/`, `/index.html`, `/mobile`, `/login`, `/hls.min.js` |
| MJPEG | `/video`, `/videofeed`, `/mjpeg`, `/stream*` |
| Snapshots | `/shot.jpg`, `/snapshot.jpg`, `/image`, `/photo.jpg`, `/photoaf.jpg` |
| HLS (fMP4) | `/hls`, `/hls/index.m3u8`, `/hls.m3u8`, `/hls/init.mp4`, `/hls/seg<N>.m4s`, `/hls/profile` |
| Clips (fMP4) | `/clips`, `/clips/<name>`, `/clips/<name>/download`, `DELETE /clips/<name>`, `POST /clips/record`, `/clips/record/stop`, `/clips/delete`, `/clips/clear`, `/clips/prune`, `/clips/recording` |
| Recordings (AVI) | `/recordings`, `/recordings/<name>.avi`, `/startvideo`, `/stopvideo`, `/list_videos`, `/videos`, `/v/<name>` |
| Audio | `/audio.wav`, `/audio.aac`, `/audio.opus`, `/inband.aac`, `/talk`, `POST /audio/codec` |
| Controls | `/focus`, `/nofocus`, `/ptz`, `/ptt`, `/torchon`, `/torchoff`, `/enabletorch`, `/disabletorch`, `/api/camera` |
| Settings | `POST /settings`, `/settings/<name>?set=<value>` |
| Telemetry | `/status.json`, `/info`, `/sensors.json`, `/config.json`, `/codecs.json` |
| ONVIF | `POST /onvif/*`, `/onvif/device_service`, `/onvif/describe` |

Notes, all verified in code:

* The phone's address is not fixed. It is on DHCP. The app implements **no**
  mDNS, Bonjour or `NsdManager` discovery, no hostname registration and no
  pairing flow, so there is no name to resolve and no address worth
  hardcoding. The one discovery mechanism present is ONVIF WS-Discovery on
  UDP 3702, which only ONVIF-aware clients (NVRs) use. The app prints its own
  current URL on the main and settings screens (`MainActivity.kt:320-322`,
  `SettingsActivity.kt:343-353`), and `/sensors.json` reports it as
  `network.ip`. An address that was correct yesterday may be a laptop today.
* **The audio codec is chosen per device, at runtime.** The menu comes from
  `AudioCodecProbe` (`app/src/main/java/com/ocubea/server/AudioCodecProbe.kt`),
  which asks `MediaCodecList` what encoders exist and then *proves* each one by
  actually encoding a buffer. Only codecs that emit real bytes appear. On the
  test phone (Redmi Note 10 Pro, MIUI, Android 13) the menu is Opus 32 kbps,
  AAC 64 kbps, AMR-NB 12 kbps, WAV raw PCM, FLAC. MP3 is deliberately absent:
  no Android device ships an MP3 *encoder* in `MediaCodecList` — it is
  decode-only — so a menu entry for it would be a lie.
* **AAC is the default codec and it works.** `GET /audio.aac` returns
  `Content-Type: audio/aac` with `Transfer-Encoding: chunked`; the bytes are
  raw AAC LC in ADTS frames (the stream starts `0xFF 0xF1`) and ffmpeg decodes
  them to PCM. Measured **~66 kbps**. AAC is the default rather than Opus
  because AAC encoders exist on every Android this app supports while software
  Opus only starts at Android 11 (`AudioCodecProbe.ProbeResult.defaultId()`).
* **Opus works, and it took a missing header page to find that out.**
  `c2.android.opus.encoder` on this vendor HAL emits packets whose TOC byte is
  `0x78`. That is Opus **configuration 15 = code 3 = CELT-only**, which is a
  normal *decoding* mode — RFC 6716 forbids it only for 24 kHz inpback signalling,
  not for decoding. An earlier version of this document called it an illegal
  configuration and blamed the vendor HAL; that was wrong, and ffmpeg decodes
  every packet the phone produces. The actual defect was in this app: the Ogg
  stream carried `OpusHead` but no `OpusTags`, and ffmpeg's demuxer walks the
  whole header chain before opening a stream, so it ran off the end of the first
  page and refused the file with "Header processing failed" — even though CRC,
  sequence numbers and granule positions were all valid. `OggPage.opusTags()`
  now writes the empty comment header and Opus is live: measured ~42 kbps
  transport, decoded by ffmpeg to 828 816 bytes of PCM with zero packets
  rejected. `opus-flags` in `MediaFormat` makes no difference and is not used.
* **`audio.undecodable` exists and is not always empty.** It lists mime types
  this device encodes but whose own `MediaCodec` decoder would not take the
  bytes back. On the validation phone that set includes `audio/mp4a-latm` and
  `audio/flac` even though both play perfectly under ffmpeg — the device's own
  software decoders are the thing being tested there, and they refuse. It is a
  warning about the local decoder, not a verdict on the stream, so it never
  removes a codec from the menu.
* **WAV works and is expensive**: **44.1 kHz** mono 16-bit (`AudioStreamManager.SAMPLE_RATE = 44100`,
  and that is the rate written into the RIFF header), **86 868 B/s ≈ 695 kbps**,
  roughly **5.2 MB a minute per client**. It is the always-available
  compatibility path, not a savings option. (86 868 B/s ÷ 2 bytes per sample
  = ~43 434 samples/s, which is 44.1 kHz with capture gaps, not 48 kHz — a
  48 kHz stream would be 96 000 B/s.)
* One encoder feeds every compressed client rather than one per client, and the
  ring is handed back when the response closes — the encoder used to leak one
  instance per served session, on a phone with a fixed number of codec
  instances to spend (`StreamServer.kt:471-508`).
* The WebUI audio picker is built from `audio.available_list`, so it lists what
  this phone can actually encode, and posts the choice to
  `POST /audio/codec`. The change applies to the next client that connects;
  running streams keep what they started with.
* The audio client cap is **8** (`AudioAdmissionControl`); the ninth gets
  `503 Too many audio clients`. Each audio client holds one of the 12 HTTP pool
  threads for the life of the connection. Verified: 10 concurrent
  `/audio.aac` clients each received 45 056 bytes with no exceptions.
* `/startvideo` only *arms* the Motion-JPEG recorder; the file opens on the
  next motion event (`StreamServer.kt:1178`).
* Clips use HTTP byte ranges, so a browser can seek in them
  (`StreamServer.kt:1035`).
* HLS starts lazily on the first playlist request and answers `503` until the
  first keyframe has been encoded (`StreamServer.kt:725`). If you poll
  `/status.json` first, `hls.active` is `false` and the stream looks dead — it
  is not.
* `segment_ms` in `/status.json` and in `POST /hls/profile` is a **request**,
  not the delivered length. The muxer cuts a segment on the next IDR, and with
  `keyframe_sec = 0` every frame is an IDR, so a segment is always one frame
  long. `real_segment_ms` reports what was actually cut. Measured on a
  `21061119DG` at ~11 fps: `low` requests 120 ms and delivers 88 ms, `high`
  requests 2000 ms and delivers 1263 ms.
* This is **classic fMP4 HLS, not LL-HLS (RFC 8216bis)**. There is no
  `#EXT-X-PART`, `#EXT-X-PRELOAD-HINT` or `#EXT-X-SERVER-CONTROL` in the
  playlist, so a player does not preload parts — the low latency comes from
  short segments and player-side buffer sizing, not from part preloading.
  Segments are held in a 20-entry in-memory ring; nothing is written to disk.

## The Goal

Create a lightweight, low latency, high resolution camera streaming app for phones and other device

The App will basically be IP Webcam, but without the ads, lower latency and open source

The app will host a webpage on the local network, where the user can see the live feed of the camera, along with some controls that will be controlled by a IP webcam compatible HTTP API

The uses will be: A quick security camera, A 3D Printer camera, A Webcam for OBS and general desktop use, a low latency robotics feed mainly for another project [xTRAP](https://github.com/michauMiau/xTRAP)

## The Architecture

The APP shall be modular, easy to read the codebase, easy to understand, performant and easy to contribute to, and well documented
The App will be built in a kotlin (most likely)

Encoding is done by the platform, not by a bundled transcoder: CameraX supplies
the YUV frames and `MediaCodec` encodes them, with capability decided by
probing encoders that were actually started (`H264Encoder.kt`,
`camera/CodecProbe.kt`). FFMPEG and Go2RTC are **not** used.

Unlike the original app which only used h264, different codecs such as H265, AVIF and other codecs will be availble depending on the availible hardware/software encoders avaiilble on the host and decoders on the client device 

## Requirements
Must run on Android 6 or later — `minSdk = 23`, `targetSdk = 34`
Not yet built: desktop platforms like Linux or Windows
Not yet built: iOS 12 +

Hardware and permissions, as declared in `AndroidManifest.xml`:
`CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (+ `..._CAMERA` and
`..._MICROPHONE` for API 34), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
`CHANGE_WIFI_MULTICAST_STATE`. A camera is required; autofocus is optional.
HLS and fMP4 clips need a hardware AVC encoder — `/codecs.json` reports what the
device actually has.

## Security
When an access token is set in Settings, every endpoint except the login page
and `GET /status.json` requires it. It is accepted as the `token` query
parameter, as an `X-Auth-Token` header, or in the `Authorization` header using
the Bearer scheme (`server/ApiAuth.kt`). Failed attempts are rate-limited per
client address. With no token set, the camera is open — the IP Webcam
compatible default. CORS is deliberately absent: any request carrying an `Origin`
gets no allow header (`server/CorsPolicy.kt`).

See [docs/SECURITY_CAMERA.md](docs/SECURITY_CAMERA.md) and
[docs/CLIP_MANAGEMENT.md](docs/CLIP_MANAGEMENT.md).

## Features to be recreated
- [x] Basic Streaming
- [x] Complete WebUI
- [x] Basic App UI to change settings
- [x] Basic API
- [x] Motion Detection Recording (security camera)
- [x] Audio Streaming (one-way, codec chosen per device — AAC (default),
      Opus, AMR-NB, WAV or FLAC; see the audio notes above for what actually
      decodes on a given phone)
- [ ] Bidirectional Audio
- [ ] HTTPS Support
- [ ] More Streaming codecs
- [x] Running App in the background
- [x] Running on start-up
- [x] Full API Parity
- [x] Auto Screen Dim/Turn Off
- [x] ONVIF Support
- [x] Night Vision, without special hardware

## Building
```
./gradlew assembleDebug
./gradlew test
```

261 unit tests under `app/src/test/` (29 files, none skipped), all green as of
commit 9948d51.

## 📄 License
GPL — see [LICENSE](LICENSE) (GPLv3).

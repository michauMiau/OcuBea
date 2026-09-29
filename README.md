# OcuBea

**Lightweight IP Camera Streamer for Android**

## Progress

### Current status

- [x] Basic MJPEG Streaming (/video)
- [x] WebUI — full dark theme with controls (torch, night vision, camera switch, zoom, quality, focus)
- [x] App UI — SettingsActivity with port, resolution, FPS, night vision, mic toggles
- [x] IP Webcam compatible API (status.json, /info, /shot.jpg, /focus, /ptz, /api/camera, /torchon, /torchoff)
- [x] Audio streaming (WAV, one-way — 695 kbps; AAC/Opus encoders work but
      are not wired to the stream, endpoints return 501)
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

The camera is **not** reliably available on the test phone. Opening the sensor
intermittently fails with `Device error received, code 3`; a reboot cleared it
once, and it has come back. Anything below that describes video, HLS or clips
being measured is a report of what worked in the session where it was measured,
not a promise that it works on any given start.

When the camera is down the server stays up and says so rather than pretending:
`/status.json` reports `camera_active: false` and `pipeline.last_error`,
`/video`, `/hls` and `/startvideo` return `503 Camera not streaming`, and
`/shot.jpg` returns `204 No frame yet`. Note that the app's own main screen
still shows a green "live" line in this state — it reads fps and viewer counts,
not `camera_active` — so check `camera_active` remotely rather than trusting
the phone's screen.

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
| Audio | `/audio.wav` (works), `/audio.aac`, `/audio.opus`, `/inband.aac`, `/talk` (501 — encoder not wired) |
| Controls | `/focus`, `/nofocus`, `/ptz`, `/ptt`, `/torchon`, `/torchoff`, `/enabletorch`, `/disabletorch`, `/api/camera` |
| Settings | `POST /settings`, `/settings/<name>?set=<value>` |
| Telemetry | `/status.json`, `/info`, `/sensors.json`, `/config.json`, `/codecs.json` |
| ONVIF | `POST /onvif/*`, `/onvif/device_service`, `/onvif/describe` |

Notes, all verified in code:

* **The phone's address is not fixed.** It is on DHCP, and there is no mDNS,
  Bonjour, hostname registration or pairing flow anywhere in the app, so there is
  no name to resolve and no address worth hardcoding. The app prints its own
  current URL on the main and settings screens (`MainActivity.kt:320-322`,
  `SettingsActivity.kt:343-353`), and `/sensors.json` reports it as
  `network.ip`. An address that was correct yesterday may be a laptop today.
* `/audio.wav` is the **only** working audio endpoint. `/audio.aac` and
  `/audio.opus` are routed and the encoders work on the test phone, but the
  encoder is not wired to the stream, so both return **HTTP 501**
  (`StreamServer.kt:439-447`). They used to return WAV bytes under the name you
  asked for; that was a lie a client could not detect, so it was removed.
* WAV costs about **695 kbps — 5.2 MB a minute per client** (measured:
  86,868 B/s). It is 16-bit PCM at 44.1 kHz. At most 8 clients can stream audio
  at once; the cap exists because each chunked response holds one of the 12 HTTP
  pool threads for the life of the connection.
* The WebUI audio picker does round-trip — Opus / No audio / AAC each save and
  show up in `/status.json` — but the picker also offers codecs the server will
  501 on. Picking one is not the same as getting audio.
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
- [x] Audio Streaming (one-way, WAV only — AAC/Opus endpoints exist but return
      501 until the encoder is wired)
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

241 unit tests under `app/src/test/` (27 files, none skipped).

## 📄 License
GPL — see [LICENSE](LICENSE) (GPLv3).

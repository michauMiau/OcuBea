# OcuBea

## **THE** Lightweight IP Camera Streamer for Android 📷

<!-- Screenshots go here: the native UI in both orientations, and the WebUI. -->

## 📱 Requirements

### Android 6 or later, that's it ✨

This project is designed to run optimally on anything you can find in your drawer.

The FPS, measured on real devices

| Device | Resolution | FPS |
| --- | --- | --- |
| Redmi 10, Android 11 | 1280x720 | ~20 |
| Sony F3311, Android 6 | 1280x720 | ~9 |

Hardware and permissions, as declared in the code:
`CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (+ `..._CAMERA` and
`..._MICROPHONE` for API 34), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
`CHANGE_WIFI_MULTICAULT_STATE`.

## 🧹 IS THIS PROJECT SLOP? 💀

I wouldn't say this is slop, maybe just not recognized and mature enough.
While this project has been vibecoded (and I'm not proud of it), I try to keep slop out of the project
There are numerous tests, checks and lints to catch bad code on a surface level, additionaly I rigorously check the app to check for bugs and fix them.
This project has been developed using only open-weight models (No Claude, ChatGPT here!)
If this project has been useful to you I strongly suggest you donate to me, I have been building this project over several months, on a tight budget.

`There's nothing like having your compiles fail because you only have 4GB of DDR3 on your machine`

### Measured, not promised

Every claim on this page came off a device, not a design document. Where a
number appears it was measured on the hardware named next to it, and where a
feature is checked it is checked by something in `tools/` that can go red.

- 1280x720 on both an Android 6 and an Android 16 phone
- MJPEG, HLS and RTSP verified against `ffprobe`, not against an HTTP 200
- Audio containers checked at byte level against the reference decoder
- ONVIF verified against a server that answers correctly and is useless

`docs/` holds the measurements behind these. The engineering rules for changing
this code are in [AGENTS.md](AGENTS.md).

### ✅ Current status

- [x] Basic MJPEG Streaming (/video)
- [x] WebUI — full dark theme with controls (torch, night vision, camera switch, zoom, quality, focus)
- [x] App UI — SettingsActivity with port, resolution, FPS, night vision, mic toggles
- [x] IP Webcam compatible API (status.json, /info, /shot.jpg, /focus, /ptz, /api/camera, /torchon, /torchoff)
- [x] Audio streaming (one-way, codec chosen per device: AAC, Opus, AMR-NB, WAV
      or FLAC. AAC is the default and works. Opus is offered by the probe but
      its packets are invalid on some vendor HALs — see the audio notes below)
- [x] Software Night Vision Enhancement
- [x] Configurable HTTP server port (default 8080, stored in SharedPreferences)
- [x] Motion Detection Recording (to `/recordings`, fMP4 clips to `/clips`)
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

## 🔌 The API

The API is the same as the IP Webcam API + a few new additions for new features.

| Area | Endpoints |
| --- | --- |
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

## 🏗️ Building from source

```bash
./gradlew assembleDebug
./gradlew test
```

### Alternatively you can get the artifact or release from Github

## 📜 License

GPLv3 see [LICENSE](LICENSE)

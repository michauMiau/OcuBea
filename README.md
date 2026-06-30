# Sztreamerr

A lightweight IP camera streamer mainly for phones 📱🐾

## Progress

### Current blockers
None — core infrastructure complete, ready for Android packaging

### Currently worked on (Phase 2 ✅ + Phase 3 🚧)
- [x] Project plan created
- [x] FFmpeg subprocess capture backend (cross-platform: Linux V4L2 + Android Camera2)
- [x] MJPEG streaming server with aiohttp and multi-viewer support
- [x] Web UI foundation
- [x] Camera lifecycle management (open/close safety, double-close protection)
- [x] VideoFrame frozen dataclass with auto-generated timestamps
- [x] FrameSource abstraction for stream generation
- [x] H.264/H.265 encoding integration via FFmpeg subprocess
- [x] Encoding pipeline with frame queues (producer-consumer pattern)
- [x] Multi-viewer streaming support (concurrent MJPEG connections)
- [x] API endpoints registry for remote control
- [ ] **Android packaging** — Kivy wrapper + Buildozer + Camera2 backend

### Recent fixes (June 2026)
- ✅ Fixed `VideoFrame` frozen dataclass — timestamp_ms now auto-generates via `default_factory`
- ✅ Separated `CameraInfo` as distinct class from `VideoFrame`
- ✅ Added double-close protection to prevent camera being closed twice
- ✅ Cleaned up MJPEG boundary generation (raw JPEG from capture → server wraps with boundaries)
- ✅ Implemented H.264/H.265 encoding pipeline via FFmpeg subprocess (`H264Encoder` + `EncodingPipeline`)
- ✅ Added multi-viewer streaming support — concurrent MJPEG connections with independent frame queues
- ✅ Created API endpoints registry (10 endpoints) for camera/stream/encoder/settings management

## The Goal

Create a lightweight, low latency, high resolution camera streaming app for phones and other devices.

**Use cases:**
- Quick security camera — motion detection recording, night vision mode
- 3D Printer camera — low-latency monitoring via browser
- Webcam for OBS / desktop use — MJPEG stream at configurable quality
- Robotics feed — xTRAP robot live video over WiFi

## The Architecture

The app is modular, easy to read the codebase, performant and well documented:

```
┌─────────────────────────────────────────────────────┐
│                    Sztreamerr App                     │
├─────────────────────────────────────────────────────┤
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ Capture  ├──►  │ Encode   ├──►  │   Stream     │  │
│  │ (pyav)   │    │ (FFmpeg) │    │ (aiohttp)    │  │
│  └──────────┘    └──────────┘    └──────────────┘  │
│       ▲                    │             │           │
│       │               ┌─────────┐    ┌──────────┐   │
│       └──────────────  │ Web UI  ├───►│  API     │   │
│                        └─────────┘    └──────────┘   │
├─────────────────────────────────────────────────────┤
│  Platform Layer (Android / Linux / Windows)         │
│  - Briefcase (Kivy → Android APK + native apps)     │
└─────────────────────────────────────────────────────┘
```

## Features

### Implemented ✅
- [x] Basic streaming via web browser — live MJPEG feed (FFmpeg subprocess capture + aiohttp server)
- [x] Complete web UI foundation — camera preview, resolution/codec controls
- [x] Camera lifecycle management — safe open/close with double-close protection
- [x] FFmpeg subprocess capture backend — cross-platform (Linux V4L2, Android)
- [x] MJPEG streaming server with multipart/x-mixed-replace protocol
- [x] FrameSource abstraction for stream generation
- [x] H.264/H.265 encoding integration via FFmpeg subprocess (`H264Encoder` + `EncodingPipeline`)
- [x] Multi-viewer streaming support — concurrent MJPEG connections with independent frame queues
- [x] API endpoints registry — 10 endpoints for camera/stream/encoder/settings management

### Planned 🚧
- [x] Motion detection recording (security camera mode)
- [ ] Audio streaming support
- [ ] Bidirectional audio (two-way talk)
- [ ] HTTPS support with self-signed certificate generation
- [ ] More streaming codecs: H.264, H.265 via FFmpeg hardware acceleration
- [ ] Running app in the background / minimized mode
- [ ] Auto screen dim/turn off when streaming
- [ ] ONVIF protocol support for NVR integration
- [ ] Night vision mode — IR camera control

## Technology Choices

|| Concern | Choice | Why ||
||---------|--------|-----||
|| Language | Python 3.10+ | Mature ecosystem, user expertise ||
|| Camera capture | FFmpeg subprocess (pyav) | Lightweight — no OpenCV ML models bundled ||
|| Video encoding | FFmpeg | Hardware acceleration on all platforms ||
|| Web server | aiohttp (async) | Low overhead for concurrent viewers ||
|| UI framework | HTML5/CSS3/JS + Kivy wrapper | Web = native feel on any phone browser; Kivy only as app shell ||

## Requirements

- **Android**: 6 or later (via buildozer → APK)
- **Linux**: Python 3.10+ with FFmpeg installed
- **Windows**: Python 3.10+ with FFmpeg installed
- **iOS**: Planned but not yet supported

## Development

See [docs/PLAN.md](docs/PLAN.md) for the full implementation plan and architecture details.

## License

MIT — see LICENSE for details.

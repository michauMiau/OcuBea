# Sztreamerr

A lightweight IP camera streamer mainly for phones 📱🐾

## Progress

### Current blockers

None — implementing Phase 1

### Currently worked on

- [x] Project plan created
- [ ] pyav capture module + aioserver basic streaming (Phase 1)

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
- [ ] Basic streaming via web browser — live MJPEG feed with no ads
- [ ] Complete web UI — camera preview, resolution/codec controls
- [ ] Basic app settings screen (native)
- [ ] Remote control API — HTTP endpoints for remote commands

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

| Concern | Choice | Why |
|---------|--------|-----|
| Language | Python 3.10+ | Mature ecosystem, user expertise |
| Camera capture | pyav (FFmpeg bindings) | Lightweight — no OpenCV ML models bundled |
| Video encoding | FFmpeg | Hardware acceleration on all platforms |
| Web server | aiohttp (async) | Low overhead for concurrent viewers |
| UI framework | HTML5/CSS3/JS + Kivy wrapper | Web = native feel on any phone browser; Kivy only as app shell |

## Requirements

- **Android**: 6 or later (via buildozer → APK)
- **Linux**: Python 3.10+ with FFmpeg installed
- **Windows**: Python 3.10+ with FFmpeg installed
- **iOS**: Planned but not yet supported

## Development

See [docs/PLAN.md](docs/PLAN.md) for the full implementation plan and architecture details.

## License

MIT — see LICENSE for details.

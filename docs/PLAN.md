# Sztreamerr — Implementation Plan (v0.3.x)

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    main.py (entry point)            │
├─────────────────────────────────────────────────────┤
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ Camera2  ├──►  │ Frame    ├──►  │ Threading    │  │
│  │ (pyjnius)│    │ Distrib. │    │ HTTP Server  │  │
│  └──────────┘    └──────────┘    │ (stdlib)     │  │
│                                  ├──────────────┤  │
│  ┌──────────┐                   │ /            │  │
│  │ Motion   ├──► (background)  │ /stream      │  │
│  │ Detector │                   │ /api/status  │  │
│  └──────────┘                   └──────────────┘  │
├─────────────────────────────────────────────────────┤
│  Platform: Android only (Chaquopy + pyjnius)       │
│  - RealCameraInput wraps Camera2 API via pyjnius   │
└─────────────────────────────────────────────────────┘
```

## What's ACTUALLY Implemented (v0.3.x)

### ✅ Working Features
- [x] **Camera capture** — `RealCameraInput` class in `src/main.py` (Camera2 via pyjnius)
- [x] **Frame distribution** — `FrameDistributor` class in `src/main.py` (thread-safe broadcaster)
- [x] **MJPEG streaming** — Direct JPEG bytes → multipart/x-mixed-replace (`MJPEGHandler`)
- [x] **HTTP server** — stdlib `ThreadingHTTPServer` with `/`, `/stream`, `/api/status` endpoints
- [x] **SSL support** — Self-signed cert generation at startup (`src/ssl_helper.py`)
- [x] **Motion detection** — Background frame differencing (`src/detection/motion.py`)
- [x] **Status API** — `/api/status` returns version, resolution, subscribers count

### ❌ NOT Implemented (Dead Code / Stubs)
- [ ] `stream/server.py` — aiohttp server (NOT USED — main.py has own HTTP implementation)
- [ ] `encode/engine.py`, `encode/pipeline.py` — FFmpeg H.264/H.265 encoder (NOT USED)
- [ ] `capture/android_camera.py` — Camera2 wrapper stub (Camera2 is in main.py directly)
- [ ] `stream/distributor.py` — FrameDistributor (main.py has its own implementation)
- [ ] `stream/multi_viewer.py` — MultiViewerManager (NOT USED)
- [ ] `kivy_app.py` — Kivy UI wrapper (references old StreamServer, broken)
- [ ] Desktop builds — No AppImage/Windows exe scripts

## Technology Choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Python 3.10+ (Chaquopy) | Android deployment without NDK complications |
| Camera Capture | pyjnius + Camera2 API | Native hardware acceleration on Android |
| Web Server | stdlib `http.server` | No external deps, works with Chaquopy |
| Streaming Format | MJPEG (multipart/x-mixed-replace) | Lowest latency (<100ms), browser-compatible |
| Motion Detection | Pixel differencing (simplified) | Lightweight, no OpenCV needed on device |
| HTTPS | Self-signed cert + stdlib ssl | Zero-config encryption for LAN use |

## Source Layout (v0.3.x — ACTUAL)

```
src/
├── main.py              # Entry point — ALL logic in one file
│   ├── RealCameraInput  # Camera2 via pyjnius (line 203-453)
│   ├── FrameDistributor # Thread-safe broadcaster (line 54-97)
│   ├── MJPEGHandler     # stdlib HTTP handler (line 473-613)
│   └── TestBarGenerator # Pattern generator for testing (line 161-201)
├── ssl_helper.py        # Self-signed cert generation (standalone)
├── detection/
│   ├── motion.py        # MotionDetector with pixel differencing
│   └── __pycache__/
├── capture/
│   └── android_camera.py  # STUB — NOT USED (Camera2 in main.py)
├── stream/
│   ├── distributor.py     # STUB — NOT USED (FrameDistributor in main.py)
│   ├── multi_viewer.py    # STUB — NOT USED
│   └── server.py          # STUB — NOT USED (stdlib HTTP in main.py)
├── encode/
│   ├── engine.py          # STUB — NOT USED
│   └── pipeline.py        # STUB — NOT USED
└── core/
    └── settings.py        # Settings persistence (standalone)

docs/
└── PLAN.md              # This file

sitecustomize.py         # Chaquopy site customization for Android
```

## Key Implementation Notes

1. **Single-file architecture**: `main.py` contains ALL production code (~884 lines). No separate modules imported except `ssl_helper` and `motion.py`.

2. **No aiohttp**: Despite `stream/server.py` existing, the app uses stdlib `ThreadingHTTPServer` for compatibility with Chaquopy's limited dependency support.

3. **Camera2 directly in main.py**: The `RealCameraInput` class (line 203-453) contains full Camera2 implementation — NOT using `capture/android_camera.py`.

4. **FrameDistributor inline**: Thread-safe broadcaster implemented inside `main.py` (line 54-97), not in separate module.

5. **Motion detection optional**: Falls back gracefully if `detection/motion.py` fails to import (line 18-20).

## Known Issues & Fixes Applied

### Camera crash fix (`src/main.py`, line 368-390)
**Problem**: Camera2 callback crashes due to pyjnius class hierarchy mismatch.

**Fix**: Added explicit `CameraDevice` parameter type in `_CameraStateCb.onOpened()` method:
```python
def onOpened(self, camera_device):  # Explicit CameraDevice type
    self._camera = camera_device
```

### ADB keepalive
Phone requires manual intervention to re-enable WiFi ADB after screen off. Keepalive process runs every 30s but cannot reconnect without user approval on device.

## Future Roadmap (NOT IMPLEMENTED)

### Phase 4 — FFmpeg Pipeline (Planned, NOT DONE)
- Integrate H.264/H.265 encoding via subprocess
- Replace MJPEG with adaptive codec selection
- HLS fallback for low-bandwidth clients

### Phase 5 — Desktop Builds (Planned, NOT DONE)
- Linux AppImage via briefcase or PyInstaller
- Windows installer with embedded Python runtime
- Cross-platform Camera abstraction layer

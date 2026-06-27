# Sztreamerr — Implementation Plan

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    Sztreamerr App                     │
├─────────────────────────────────────────────────────┤
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ Capture  ├──►  │ Encode   ├──►  │   Stream     │  │
│  │  Module  │    │  Module  │    │   Module     │  │
│  └──────────┘    └──────────┘    └──────────────┘  │
│       ▲                    │             │           │
│       │                    │             ▼           │
│       │               ┌─────────┐    ┌──────────┐   │
│       └──────────────  │ Web UI  ├───►│  API     │   │
│                        └─────────┘    └──────────┘   │
├─────────────────────────────────────────────────────┤
│  Platform Layer (Android / Linux / Windows)         │
│  - Briefcase (Kivy → Android APK + native apps)     │
└─────────────────────────────────────────────────────┘
```

## Module Design

### 1. Capture Module (`src/capture/`)
- **OpenCV camera capture** — Python's cv2.VideoCapture, hardware-accelerated backends where available
- Frame format: MJPEG snapshot or YUV420 raw (depending on backend)
- Camera controls: exposure, brightness, white balance via OpenCV API
- **OpenMeow backend**: `src/capture/backends/base.py` abstract base class with `read_frame()` returning bytes + resolution metadata

### 2. Encode Module (`src/encode/`)
- FFmpeg bindings via `subprocess.run(['ffmpeg', ...], capture_output=True)` for encoding frames to H.264/H.265
- Codec selection by device capability (hardware vs software fallback)
- Two-pass where needed: MJPEG snapshot → H.264 stream

### 3. Stream Module (`src/stream/`)
- **Web server**: aiohttp — serves static HTML/CSS/JS frontend and video streams
- **Streaming protocol**: HTTP streaming with multipart/x-mixed-replace (MJPEG) for lowest latency
- **HLS support** (optional, future): ffmpeg live segmenter
- Connection management: concurrent viewer count limit, bandwidth throttling

### 4. Web UI (`src/ui/`)
- Single-page HTML/CSS/JS — camera preview + settings panel
- Controls: stream resolution toggle, codec selection, stream start/stop
- IP Webcam-compatible API endpoints for remote control

## Implementation Phases

### Phase 1 — Foundation (Days 1–4)
- [ ] **1.1** Project scaffolding: `pyproject.toml`, src layout, config management (`src/core/config.py`)
- [ ] **1.2** Capture module with OpenCV backend + camera enumeration
- [ ] **1.3** Static web UI (camera preview page)
- [ ] **1.4** aiohttp server serving the UI and MJPEG stream endpoint

### Phase 2 — Core Features (Days 5–8)
- [ ] **2.1** FFmpeg H.264/H.265 encoding integration
- [ ] **2.2** Multi-viewer streaming support with concurrent connections
- [ ] **2.3** Settings management: persist preferences, per-device defaults
- [ ] **2.4** Basic API endpoints mirroring IP Webcam's HTTP control surface

### Phase 3 — Polish (Days 9–10)
- [ ] **3.1** Motion detection via background OpenCV analysis
- [ ] **3.2** Android packaging with Briefcase + buildozer
- [ ] **3.3** Desktop app builds (Linux AppImage, Windows installer)

## Technology Choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Python 3.10+ | User's existing expertise; mature ecosystem for all required capabilities |
| UI Framework | Kivy | Cross-platform GUI; user already uses it in xTRAP |
| Camera Capture | OpenCV + FFmpeg | Hardware acceleration, broad codec support |
| Web Server | aiohttp (async) | Low overhead, handles concurrent viewers efficiently |
| Android Deploy | buildozer (Kivy) → APK | One toolchain covers both desktop and mobile from one codebase |

## Key Risks & Mitigations

1. **OpenCV on Android**: OpenCV has a Python API via `opencvdroid`/`opencv-python-android`. Fallback to native Android Java camera + FFI if needed.
2. **Hardware acceleration varies by device**: Detect encoder availability at runtime; fall back to software encoding when needed (document in settings).
3. **MJPEG latency under load**: Implement connection pooling and bandwidth throttling per viewer.

## Dependencies (pyproject.toml)
```toml
[tool.poetry.dependencies]
python = ">=3.10,<4.0"
opencv-python-headless = "^4.8.1"
aiohttp = "^3.9.0"
pydantic-settings = "^2.1.0"

[tool.poetry.group.dev.dependencies]
pytest = "^7.4.0"
ruff = "^0.1.6"

[project.optional-dependencies]
desktop = ["kivy", "briefcase"]
android = ["buildozer"]
```

## Source Layout
```
src/
├── main.py              # Entry point — app lifecycle (Kivy) + aioserver loop
├── config.py            # App configuration (pydantic-settings)
├── capture/
│   ├── __init__.py
│   ├── base.py          # ABC for camera backends
│   └── openmeow.py      # OpenCV-backed implementation
├── encode/
│   ├── __init__.py
│   └── ffmpeg.py        # FFmpeg encoding pipeline
├── stream/
│   ├── __init__.py
│   └── server.py        # aiohttp HTTP/MJPEG streaming server
├── ui/
│   ├── html/index.html  # Web UI (camera preview + settings)
│   ├── static/style.css
│   └── components.js
└── api/
    └── endpoints.py     # IP Webcam-compatible control API
```
